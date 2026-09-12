package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Coordinates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AliasGeneratorTest {
    @Test
    fun `generates deterministic kebab case from artifact`() {
        assertThat(AliasGenerator().generate(Coordinates("org.example", "HTTP_Client_core"), emptySet()))
            .isEqualTo("http-client-core")
    }

    @Test
    fun `uses minimal stable group token when accessor collides`() {
        val alias = AliasGenerator().generate(
            Coordinates("com.squareup.okhttp3", "logging-interceptor"),
            setOf("logging.interceptor"),
        )

        assertThat(alias).isEqualTo("okhttp3-logging-interceptor")
    }

    @Test
    fun `returns no alias when every deterministic option is ambiguous`() {
        val alias = AliasGenerator().generate(
            Coordinates("com.example", "library"),
            setOf("library", "example-library"),
        )

        assertThat(alias).isNull()
    }
}
