package com.emsafe.bdd.steps;

import com.emsafe.devicemanagement.application.DeviceActor;
import com.emsafe.devicemanagement.interfaces.rest.DeviceActorResolver;
import com.emsafe.shared.exception.BadRequestException;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Estado compartido del escenario: quién actúa y si su última solicitud fue rechazada. */
public class SolicitudSteps {
    public static final String SSO_EMAIL = "sso@quantumdyn.com";
    public static final String OTRO_CLIENTE_EMAIL = "facilities@harbormed.com";

    @Autowired DeviceActorResolver actors;

    private RuntimeException rechazo;

    /** Actor tal como lo arma la API a partir del usuario autenticado. */
    public DeviceActor actor(String email) {
        return actors.resolve(new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    public void intentar(Runnable solicitud) {
        rechazo = null;
        try {
            solicitud.run();
        } catch (BadRequestException | AccessDeniedException e) {
            rechazo = e;
        }
    }

    public RuntimeException rechazo() {
        return rechazo;
    }

    @Entonces("la solicitud es rechazada")
    public void laSolicitudEsRechazada() {
        assertThat(rechazo).as("la solicitud debía ser rechazada").isNotNull();
    }
}
