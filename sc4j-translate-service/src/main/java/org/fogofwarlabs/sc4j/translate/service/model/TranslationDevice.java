package org.fogofwarlabs.sc4j.translate.service.model;

/**
 * The devices a translation model may run on.
 */
public enum TranslationDevice {
    /**
     * A GPU when the model supports one and the machine has one, else the CPU.
     */
    AUTOMATIC,
    /**
     * The CPU only.
     */
    CPU
}
