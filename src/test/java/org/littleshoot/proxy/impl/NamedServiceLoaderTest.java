package org.littleshoot.proxy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NamedServiceLoaderTest {

  @Test
  void shouldSelectRequestedServiceByName() {
    NamedService fake = named("FAKE");
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(NamedService.class, Collections.singletonList(fake));

    assertThat(loader.load("FAKE")).isSameAs(fake);
  }

  @Test
  void shouldChooseCaseInsensitively() {
    NamedService fake = named("MY_CUSTOM_POOL");
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(NamedService.class, Collections.singletonList(fake));

    assertThat(loader.load("my_custom_pool")).isSameAs(fake);
  }

  @Test
  void shouldFailFastWhenRequestedNameIsUnknown() {
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(
            NamedService.class, Collections.singletonList(named("REGISTERED")));

    assertThatThrownBy(() -> loader.load("MISSING"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MISSING")
        .hasMessageContaining("REGISTERED");
  }

  @Test
  void shouldFailFastWhenNothingIsRegistered() {
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(NamedService.class, Collections.emptyList());

    assertThatThrownBy(() -> loader.load("ANY")).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest(name = "requested name ambiguous when candidates {0}")
  @MethodSource("ambiguousCandidates")
  void shouldThrowWhenRequestedNameIsAmbiguous(String description, List<NamedService> candidates) {
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(NamedService.class, candidates);

    assertThatThrownBy(() -> loader.load("DUP"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ambiguous");
  }

  static Stream<Arguments> ambiguousCandidates() {
    return Stream.of(
        Arguments.of("differ only in case", Arrays.asList(named("DUP"), named("dup"))),
        Arguments.of("are duplicated exactly", Arrays.asList(named("DUP"), named("DUP"))));
  }

  @Test
  void shouldIgnoreCollisionsBetweenUnrequestedNames() {
    // Two unrelated implementations collide, but neither is requested: selection must not care.
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(
            NamedService.class, Arrays.asList(named("DUP"), named("dup"), named("OTHER")));

    assertThat(loader.load("OTHER").getName()).isEqualTo("OTHER");
  }

  @Test
  void shouldThrowWhenNameIsBlank() {
    NamedService blank = named(null);
    NamedServiceLoader<NamedService> loader =
        new NamedServiceLoader<>(NamedService.class, Collections.singletonList(blank));

    assertThatThrownBy(() -> loader.load("X")).isInstanceOf(IllegalStateException.class);
  }

  private static NamedService named(String name) {
    NamedService service = mock(NamedService.class);
    when(service.getName()).thenReturn(name);
    return service;
  }
}
