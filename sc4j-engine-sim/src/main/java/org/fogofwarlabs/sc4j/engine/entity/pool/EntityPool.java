package org.fogofwarlabs.sc4j.engine.entity.pool;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.Entity;
import org.fogofwarlabs.sc4j.engine.entity.column.Column;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnTable;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Holds a fixed number of entity slots, {@code 1} up to the capacity, with their generations and extension columns.
 * <p>
 * Lifecycle, in this fixed order:
 * <ul>
 *     <li>Creation: the factory creates every slot object in ascending index order, the columns are created in
 *     registration order with every value at its default, and the policy links every slot into the free list.</li>
 *     <li>Allocation, in two steps: the allocating system takes the first free slot ({@link #getFirstFree()}),
 *     initializes its entity fields and then commits it ({@link #commit}); with {@link GenerationTiming#ON_INITIALIZE}
 *     it advances the generation ({@link #advanceGeneration}) between the two. The commit lets the policy unlink the
 *     slot from the free list, marks it allocated, links it after the last used slot and resets its
 *     {@link org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset#ON_ALLOCATE} columns in registration order. An
 *     initialization that fails before the commit leaves the slot free. {@link #allocate()} does the steps at once and
 *     leaves the entity fields as they are.</li>
 *     <li>{@link #free}: unlinks the slot from the used slots, marks it free, advances its generation with
 *     {@link GenerationTiming#ON_FREE} and lets the policy link it into the free list. Columns and entity fields keep
 *     their values.</li>
 * </ul>
 * The used slots are listed in allocation order ({@link #getUsedSlots()}); systems iterate them from the first to the
 * last used slot, so the iteration order is the same in every run.
 * <p>
 * A handle names a slot and its generation, so a handle kept after the entity was freed resolves to nothing: the low
 * {@code 16} bits hold the index and the high {@code 16} bits the generation.
 * <p>
 * The slot accessors that take an index ({@link #get(int)}, {@link #isAllocated(int)}, the generation accessors) are the
 * documented {@code int} form of the pool: the slot index identifies a slot inside its pool, as a saved game or a mod's
 * view of the pool in its own memory layout reads it; every other engine API names the entity.
 *
 * @param <E> the entity type
 */
public final class EntityPool<E extends Entity> implements StateHashContributor, SnapshotContributor {
    /**
     * The largest capacity: an index fits the {@code 16} index bits of a handle.
     */
    public static final int MAXIMUM_CAPACITY = 0xFFFF;

    /**
     * The number of low handle bits that hold the index.
     */
    private static final int HANDLE_INDEX_BITS = 16;
    /**
     * The mask of the index bits of a handle.
     */
    private static final int HANDLE_INDEX_MASK = 0xFFFF;
    /**
     * The mask of a generation: a stored generation keeps these bits, so it fits the generation bits of a handle.
     */
    private static final int GENERATION_MASK = 0xFFFF;

    private final int capacity;
    private final @NotNull SlotPolicy policy;

    private final @NotNull E @NotNull [] entities;
    private final @NotNull ColumnTable columns;

    private final boolean @NotNull [] allocated;
    private final int @NotNull [] generations;
    private final @NotNull SlotList freeSlots;
    private final @NotNull SlotList usedSlots;

    /**
     * Creates an {@link EntityPool} instance with every slot free.
     *
     * @param capacity the number of slots, {@code 0} up to {@link #MAXIMUM_CAPACITY}
     * @param factory  creates the entity object of each slot
     * @param policy   decides the order of the free slots and the generation rule
     * @param columns  the extension column keys in registration order
     * @throws IllegalArgumentException if the capacity is out of range, a column is listed twice or the factory creates an entity with another index
     */
    @SuppressWarnings("unchecked")
    public EntityPool(int capacity, @NotNull EntityFactory<E> factory, @NotNull SlotPolicy policy, @NotNull List<@NotNull ColumnKey<?>> columns) {
        if (capacity < 0 || capacity > MAXIMUM_CAPACITY) {
            throw Exceptions.newIllegalArgumentException("The pool capacity must be 0..{0}: {1}", MAXIMUM_CAPACITY, capacity);
        }

        this.capacity = capacity;
        this.policy   = policy;

        // The array holds only objects of the factory, all of type E.
        this.entities = (E[]) new Entity[capacity];
        this.columns  = new ColumnTable(columns, capacity + 1);

        this.allocated   = new boolean[capacity + 1];
        this.generations = new int[capacity + 1];
        this.freeSlots   = new SlotList(capacity);
        this.usedSlots   = new SlotList(capacity);

        for (int index = 1; index <= capacity; index++) {
            E entity = factory.create(index);

            if (entity.getIndex() != index) {
                throw Exceptions.newIllegalArgumentException("The entity factory created index {0} for slot {1}.", entity.getIndex(), index);
            }

            this.entities[index - 1] = entity;
        }

        policy.initialize(this.freeSlots, capacity);
    }

    /**
     * @return the number of slots
     */
    public int getCapacity() {
        return this.capacity;
    }

    /**
     * @return the number of allocated slots
     */
    public int getAllocatedCount() {
        return this.usedSlots.getSize();
    }

    /**
     * @return the policy that decides the order of the free slots and the generation rule
     */
    public @NotNull SlotPolicy getPolicy() {
        return this.policy;
    }

    /**
     * Returns the entity object of a slot, allocated or not.
     *
     * @param index the slot index, {@code 1} up to the capacity
     * @return the entity object
     * @throws IndexOutOfBoundsException if the index names no slot
     */
    public @NotNull E get(int index) {
        return this.entities[index - 1];
    }

    /**
     * Checks whether a slot is allocated.
     *
     * @param index the slot index, {@code 1} up to the capacity
     * @return {@code true} if the slot is allocated, {@code false} otherwise
     */
    public boolean isAllocated(int index) {
        return this.allocated[index];
    }

    /**
     * Returns the generation of a slot: {@code 0} for a new slot, then advanced by the generation rule of the policy and
     * kept to {@code 16} bits.
     *
     * @param index the slot index, {@code 1} up to the capacity
     * @return the generation
     */
    public int getGeneration(int index) {
        return this.generations[index];
    }

    /**
     * Sets the generation of a slot as it is, for example when a saved game is restored.
     *
     * @param index      the slot index, {@code 1} up to the capacity
     * @param generation the generation, masked to {@code 16} bits
     */
    public void setGeneration(int index, int generation) {
        this.generations[index] = generation & GENERATION_MASK;
    }

    /**
     * Returns the first free slot, the one the next allocation takes, without allocating it.
     *
     * @return the entity of the first free slot, or {@code null} if the free list is empty
     */
    public @Nullable E getFirstFree() {
        int index = this.freeSlots.getFirst();

        return index == SlotList.NONE ? null : this.entities[index - 1];
    }

    /**
     * @return the free slots in the order the next allocations take them
     */
    public @NotNull SlotList getFreeSlots() {
        return this.freeSlots;
    }

    /**
     * @return the allocated slots in allocation order
     */
    public @NotNull SlotList getUsedSlots() {
        return this.usedSlots;
    }

    /**
     * @return the extension columns of this pool
     */
    public @NotNull ColumnTable getColumns() {
        return this.columns;
    }

    /**
     * Returns an extension column of this pool. A system calls this once, when it is attached, and keeps the column.
     *
     * @param <C> the column type
     * @param key the column key
     * @return the column
     * @throws IllegalArgumentException if the key is not registered for this pool
     */
    public <C extends Column> @NotNull C getColumn(@NotNull ColumnKey<C> key) {
        return this.columns.get(key);
    }


    /**
     * Allocates the first free slot: advances its generation with {@link GenerationTiming#ON_INITIALIZE}, then commits it.
     *
     * @return the allocated entity, or {@code null} if the free list is empty
     */
    public @Nullable E allocate() {
        E entity = this.getFirstFree();

        if (entity == null) {
            return null;
        }

        if (this.policy.getGenerationTiming() == GenerationTiming.ON_INITIALIZE) {
            this.advanceGeneration(entity);
        }

        this.commit(entity);

        return entity;
    }

    /**
     * Advances the generation of the first free slot, which the allocating system initializes and has not committed yet.
     *
     * @param entity the entity of the first free slot
     * @throws IllegalArgumentException if the entity does not belong to this pool
     * @throws IllegalStateException    if the policy advances generations on free, or the entity is not the first free slot
     */
    public void advanceGeneration(@NotNull E entity) {
        if (this.policy.getGenerationTiming() != GenerationTiming.ON_INITIALIZE) {
            throw Exceptions.newIllegalStateException("The pool policy advances generations on free.");
        }

        int index = this.checkFirstFree(entity);

        this.generations[index] = this.policy.advanceGeneration(this.generations[index]) & GENERATION_MASK;
    }

    /**
     * Commits the allocation of the first free slot after the allocating system initialized it: the policy unlinks it
     * from the free list, the slot is marked allocated and linked after the last used slot, and its
     * {@link org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset#ON_ALLOCATE} columns are reset.
     *
     * @param entity the entity of the first free slot
     * @throws IllegalArgumentException if the entity does not belong to this pool
     * @throws IllegalStateException    if the entity is not the first free slot
     */
    public void commit(@NotNull E entity) {
        int index = this.checkFirstFree(entity);

        this.policy.unlink(this.freeSlots, index);
        this.allocated[index] = true;
        this.usedSlots.addLast(index);
        this.columns.resetSlot(index);
    }

    /**
     * Frees the slot of an allocated entity.
     *
     * @param entity the allocated entity of this pool
     * @throws IllegalArgumentException if the entity does not belong to this pool
     * @throws IllegalStateException    if the slot is already free
     */
    public void free(@NotNull E entity) {
        int index = this.checkEntity(entity);

        if (!this.allocated[index]) {
            throw Exceptions.newIllegalStateException("The slot {0} is already free.", index);
        }

        this.usedSlots.remove(index);
        this.allocated[index] = false;

        if (this.policy.getGenerationTiming() == GenerationTiming.ON_FREE) {
            this.generations[index] = this.policy.advanceGeneration(this.generations[index]) & GENERATION_MASK;
        }

        this.policy.release(this.freeSlots, index);
    }

    /**
     * Frees every slot without the policy, for example before a saved game is restored: every slot is unallocated with
     * generation {@code 0}, and the free and used lists are empty. Entity fields and column values keep their values.
     * The caller then marks the restored slots ({@link #restoreAllocated}) and links the free slots
     * ({@link #getFreeSlots()}).
     */
    public void clear() {
        Arrays.fill(this.allocated, false);
        Arrays.fill(this.generations, 0);

        this.freeSlots.clear();
        this.usedSlots.clear();
    }

    /**
     * Marks a slot allocated as it is, for example when a saved game is restored: the slot is linked after the last used
     * slot; the free list, the generation and the columns are not changed.
     *
     * @param index the slot index, {@code 1} up to the capacity
     * @throws IllegalStateException if the slot is already allocated
     */
    public void restoreAllocated(int index) {
        if (this.allocated[index]) {
            throw Exceptions.newIllegalStateException("The slot {0} is already allocated.", index);
        }

        this.allocated[index] = true;
        this.usedSlots.addLast(index);
    }

    /**
     * Creates the handle of an entity: its index and its current generation.
     *
     * @param entity the entity
     * @return the handle
     */
    public int createHandle(@NotNull E entity) {
        int index = entity.getIndex();

        return index | (this.generations[index] << HANDLE_INDEX_BITS);
    }

    /**
     * Resolves a handle.
     *
     * @param handle the handle
     * @return the entity if its slot is allocated and has the handle's generation, {@code null} otherwise
     */
    public @Nullable E resolveHandle(int handle) {
        int index = handle & HANDLE_INDEX_MASK;

        if (index < 1 || index > this.capacity || !this.allocated[index] || this.generations[index] != handle >>> HANDLE_INDEX_BITS) {
            return null;
        }

        return this.entities[index - 1];
    }

    /**
     * Mixes this part of the simulation state into the hash: the capacity, the ends and sizes of the free and used
     * lists, then for every slot in ascending order its allocation flag, generation, links, entity fields and column
     * values.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mix(this.capacity);
        hash.mix(this.freeSlots.getFirst());
        hash.mix(this.freeSlots.getLast());
        hash.mix(this.freeSlots.getSize());
        hash.mix(this.usedSlots.getFirst());
        hash.mix(this.usedSlots.getLast());
        hash.mix(this.usedSlots.getSize());

        for (int index = 1; index <= this.capacity; index++) {
            hash.mixBoolean(this.allocated[index]);
            hash.mix(this.generations[index]);
            hash.mix(this.freeSlots.getNext(index));
            hash.mix(this.usedSlots.getNext(index));

            this.entities[index - 1].contributeStateHash(hash);
            this.columns.contributeStateHash(hash, index);
        }
    }

    /**
     * Writes the pool: its capacity, the allocation flags and generations, the free and the used slot lists, every entity in slot order, then
     * the extension columns.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writeInt(this.capacity);
        writer.writeBooleans(this.allocated);
        writer.writeInts(this.generations);
        this.freeSlots.writeSnapshot(writer);
        this.usedSlots.writeSnapshot(writer);

        for (E entity : this.entities) {
            entity.writeSnapshot(writer);
        }

        this.columns.writeSnapshot(writer);
    }

    /**
     * Reads the pool back, replacing the slot state, every entity and the extension columns.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or holds another capacity, another column or an unknown reference
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        reader.readLength(this.capacity);
        reader.readBooleans(this.allocated);
        reader.readInts(this.generations);
        this.freeSlots.readSnapshot(reader);
        this.usedSlots.readSnapshot(reader);

        for (E entity : this.entities) {
            entity.readSnapshot(reader);
        }

        this.columns.readSnapshot(reader);
    }

    private int checkEntity(@NotNull E entity) {
        int index = entity.getIndex();

        if (index < 1 || index > this.capacity || this.entities[index - 1] != entity) {
            throw Exceptions.newIllegalArgumentException("The entity of slot {0} does not belong to this pool.", index);
        }

        return index;
    }

    private int checkFirstFree(@NotNull E entity) {
        int index = this.checkEntity(entity);

        if (index != this.freeSlots.getFirst() || this.allocated[index]) {
            throw Exceptions.newIllegalStateException("The slot {0} is not the first free slot.", index);
        }

        return index;
    }
}
