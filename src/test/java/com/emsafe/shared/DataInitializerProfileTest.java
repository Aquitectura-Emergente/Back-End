package com.emsafe.shared;

import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.devicemanagement.application.DeviceCatalog;
import com.emsafe.fieldservice.infrastructure.persistence.HistoryRepository;
import com.emsafe.fieldservice.infrastructure.persistence.WorkOrderRepository;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataInitializer siembra usuarios con contraseñas fijas: solo debe existir en dev, demo y test.
 * En prod (solo o con "ingestion") el bean no se registra y, con la tabla users vacía,
 * no se crea ningún usuario.
 */
class DataInitializerProfileTest {

    private final UserRepository users = mock(UserRepository.class);

    private ApplicationContextRunner runnerWithProfiles(String profiles) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profiles)
                .withBean(UserRepository.class, () -> users)
                .withBean(WorkOrderRepository.class, () -> mock(WorkOrderRepository.class))
                .withBean(AlertRepository.class, () -> mock(AlertRepository.class))
                .withBean(RadiationReadingRepository.class, () -> mock(RadiationReadingRepository.class))
                .withBean(HistoryRepository.class, () -> mock(HistoryRepository.class))
                .withBean(DeviceCatalog.class, () -> mock(DeviceCatalog.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withUserConfiguration(DataInitializer.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "prod,ingestion"})
    void enProdNoSeRegistraNiCreaUsuariosConLaBaseVacia(String profiles) {
        when(users.count()).thenReturn(0L);

        runnerWithProfiles(profiles).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DataInitializer.class);
            // Lo que haría Spring Boot al arrancar: ejecutar todos los CommandLineRunner.
            for (CommandLineRunner runner : context.getBeansOfType(CommandLineRunner.class).values()) {
                runner.run();
            }
        });

        verify(users, never()).save(any());
        verify(users, never()).saveAll(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "demo", "test"})
    void enDevDemoYTestSigueRegistrado(String profile) {
        runnerWithProfiles(profile).run(context ->
                assertThat(context).hasSingleBean(DataInitializer.class));
    }

    @Test
    void sinPerfilActivoNoSeRegistra() {
        new ApplicationContextRunner()
                .withUserConfiguration(DataInitializer.class)
                .run(context -> assertThat(context).doesNotHaveBean(DataInitializer.class));
    }
}
