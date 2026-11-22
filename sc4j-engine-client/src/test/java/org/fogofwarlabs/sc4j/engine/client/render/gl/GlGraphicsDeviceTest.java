package org.fogofwarlabs.sc4j.engine.client.render.gl;

import org.fogofwarlabs.sc4j.engine.client.HiddenWindowScenario;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Tests the OpenGL graphics device default on a hidden window: shader programs, their uniforms and lifetime, and the color target selection.
 */
final class GlGraphicsDeviceTest {
    private static final @NotNull String NAME = "Test";
    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         void main() {
                                                             vec2 corner = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);
                                                             gl_Position = vec4(corner, 0.0, 1.0);
                                                         }
                                                         """;
    private static final @NotNull String FRAGMENT_SHADER = """
                                                           #version 330 core
                                                           uniform vec4 color;
                                                           out vec4 fragmentColor;
                                                           void main() {
                                                               fragmentColor = color;
                                                           }
                                                           """;
    private static final @NotNull String BROKEN_SHADER = """
                                                         #version 330 core
                                                         void main() {
                                                             undefinedFunction();
                                                         }
                                                         """;
    private static final int TARGET_COUNT = 2;

    @Test
    void create_shader_program_should_find_its_uniforms() {
        HiddenWindowScenario.run(() -> {
            try (ShaderProgram program = GraphicsDevice.getCurrent().createShaderProgram(NAME, VERTEX_SHADER, FRAGMENT_SHADER)) {
                Assertions.assertTrue(program.getUniformLocation("color") >= 0);
                Assertions.assertEquals(-1, program.getUniformLocation("missing"));
            }
        });
    }

    @Test
    void close_should_release_once_and_refuse_later_use() {
        HiddenWindowScenario.run(() -> {
            ShaderProgram program = GraphicsDevice.getCurrent().createShaderProgram(NAME, VERTEX_SHADER, FRAGMENT_SHADER);

            program.close();
            program.close();

            Assertions.assertThrows(IllegalStateException.class, program::use);
            Assertions.assertThrows(IllegalStateException.class, () -> program.getUniformLocation("color"));
        });
    }

    @Test
    void create_shader_program_should_reject_a_stage_that_does_not_compile() {
        HiddenWindowScenario.run(() -> {
            IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> {
                GraphicsDevice.getCurrent().createShaderProgram(NAME, VERTEX_SHADER, BROKEN_SHADER);
            });

            Assertions.assertTrue(exception.getMessage().startsWith(NAME));
        });
    }

    @Test
    void select_color_targets_should_set_the_draw_buffers_of_the_bound_framebuffer() {
        HiddenWindowScenario.run(() -> {
            int framebuffer = glGenFramebuffers();
            int[] renderbuffers = new int[TARGET_COUNT];

            glGenRenderbuffers(renderbuffers);

            try {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);

                for (int target = 0; target < TARGET_COUNT; target++) {
                    glBindRenderbuffer(GL_RENDERBUFFER, renderbuffers[target]);
                    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, HiddenWindowScenario.SIZE, HiddenWindowScenario.SIZE);
                    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + target, GL_RENDERBUFFER, renderbuffers[target]);
                }

                Assertions.assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));

                GraphicsDevice.getCurrent().selectColorTargets(TARGET_COUNT);

                Assertions.assertEquals(GL_COLOR_ATTACHMENT0, glGetInteger(GL_DRAW_BUFFER0));
                Assertions.assertEquals(GL_COLOR_ATTACHMENT0 + 1, glGetInteger(GL_DRAW_BUFFER1));

                GraphicsDevice.getCurrent().selectColorTargets(1);

                Assertions.assertEquals(GL_NONE, glGetInteger(GL_DRAW_BUFFER1));
                Assertions.assertThrows(IndexOutOfBoundsException.class, () -> {
                    GraphicsDevice.getCurrent().selectColorTargets(GraphicsDevice.MAXIMUM_COLOR_TARGETS + 1);
                });
            } finally {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glDeleteRenderbuffers(renderbuffers);
                glDeleteFramebuffers(framebuffer);
            }
        });
    }
}
