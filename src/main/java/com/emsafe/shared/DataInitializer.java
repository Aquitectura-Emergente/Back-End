package com.emsafe.shared;

import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.radiationmonitoring.domain.services.RadiationLevel;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.fieldservice.domain.model.History;
import com.emsafe.fieldservice.infrastructure.persistence.HistoryRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.fieldservice.domain.model.*;
import com.emsafe.fieldservice.infrastructure.persistence.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final WorkOrderRepository workOrderRepository;
    private final AlertRepository alertRepository;
    private final RadiationReadingRepository radiationReadingRepository;
    private final HistoryRepository historyRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;

    // ─── Sedes de los clientes (una dirección registrada por cliente) ─────────
    /** Quantum Dynamics Lab — Av. Universitaria 1801, San Miguel. */
    private static final double QD_LAT = -12.0774, QD_LNG = -77.0869;
    /** Harbor Medical Center — Av. Javier Prado Oeste 499, San Isidro. */
    private static final double HM_LAT = -12.0960, HM_LNG = -77.0442;
    /** Global Pharma Corp — Av. El Sol 455, Villa El Salvador. */
    private static final double GP_LAT = -12.2100, GP_LNG = -76.9500;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already seeded — skipping initialization.");
            return;
        }
        log.info("Seeding database with initial data...");
        seedUsers();
        seedDevices();          // devices first so readings can reference them
        seedRadiationReadings();
        seedReadingTimeSeries();
        seedWorkOrders();
        seedAlerts();
        seedHistory();
        log.info("Database seeding complete.");
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    private void seedUsers() {
        List<AppUser> users = List.of(
                AppUser.builder()
                        .email("admin@emsafe.com")
                        .passwordHash(passwordEncoder.encode("admin123"))
                        .name("Alex Thompson").initials("AT")
                        .role(Role.ADMIN).status("active")
                        .joinDate(LocalDate.of(2023, 9, 1)).build(),

                AppUser.builder()
                        .email("marcus@emsafe.com")
                        .passwordHash(passwordEncoder.encode("tech123"))
                        .name("Marcus Rivera").initials("MR")
                        .role(Role.TECHNICIAN).status("active")
                        .joinDate(LocalDate.of(2023, 11, 1)).build(),

                AppUser.builder()
                        .email("s.jenkins@field.emsafe.com")
                        .passwordHash(passwordEncoder.encode("tech123"))
                        .name("Sarah Jenkins").initials("SJ")
                        .role(Role.TECHNICIAN).status("active")
                        .joinDate(LocalDate.of(2023, 10, 15)).build(),

                AppUser.builder()
                        .email("e.rodriguez@field.emsafe.com")
                        .passwordHash(passwordEncoder.encode("tech123"))
                        .name("Elena Rodriguez").initials("ER")
                        .role(Role.TECHNICIAN).status("active")
                        .joinDate(LocalDate.of(2023, 12, 1)).build(),

                AppUser.builder()
                        .email("j.davis@emsafe-iot.com")
                        .passwordHash(passwordEncoder.encode("admin123"))
                        .name("Julianne Davis").initials("JD")
                        .role(Role.ADMIN).status("active")
                        .joinDate(LocalDate.of(2023, 9, 15)).build(),

                AppUser.builder()
                        .email("m.silva@field.emsafe.com")
                        .passwordHash(passwordEncoder.encode("tech123"))
                        .name("Maria Silva").initials("MS")
                        .role(Role.TECHNICIAN).status("active")
                        .joinDate(LocalDate.of(2024, 1, 10)).build(),

                AppUser.builder()
                        .email("r.king@field.emsafe.com")
                        .passwordHash(passwordEncoder.encode("tech123"))
                        .name("Robert King").initials("RK")
                        .role(Role.TECHNICIAN).status("inactive")
                        .joinDate(LocalDate.of(2024, 1, 20)).build(),

                // ── Clients — each has ONE registered address with lat/lng ─────
                // Quantum Dynamics Lab — research facility at PUCP, San Miguel
                AppUser.builder()
                        .email("ops@quantumdyn.com")
                        .passwordHash(passwordEncoder.encode("client123"))
                        .name("Quantum Dynamics Lab").initials("QD")
                        .role(Role.CLIENT).status("active")
                        .joinDate(LocalDate.of(2023, 10, 1))
                        .address("Av. Universitaria 1801, San Miguel, Lima")
                        .latitude(-12.0774).longitude(-77.0869)
                        .clientType("company").country("Perú").industry("educacion").taxId("20100000001")
                        .contactName("Dr. Quantum Ops").contactEmail("ops@quantumdyn.com").contactPhone("+51-1-555-0701")
                        .build(),

                // Harbor Medical Center — private clinic in San Isidro
                AppUser.builder()
                        .email("facilities@harbormed.com")
                        .passwordHash(passwordEncoder.encode("client123"))
                        .name("Harbor Medical Center").initials("HM")
                        .role(Role.CLIENT).status("active")
                        .joinDate(LocalDate.of(2023, 11, 5))
                        .address("Av. Javier Prado Oeste 499, San Isidro, Lima")
                        .latitude(-12.0960).longitude(-77.0442)
                        .clientType("company").country("Perú").industry("salud").taxId("20100000002")
                        .contactName("Facilities Harbor").contactEmail("facilities@harbormed.com").contactPhone("+51-1-555-0702")
                        .build(),

                // Global Pharma Corp — industrial plant in Villa El Salvador
                AppUser.builder()
                        .email("safety@globalpharma.com")
                        .passwordHash(passwordEncoder.encode("client123"))
                        .name("Global Pharma Corp").initials("GP")
                        .role(Role.CLIENT).status("active")
                        .joinDate(LocalDate.of(2024, 2, 1))
                        .address("Av. El Sol 455, Villa El Salvador, Lima")
                        .latitude(-12.2100).longitude(-76.9500)
                        .clientType("company").country("Perú").industry("manufactura").taxId("20100000003")
                        .contactName("Safety Pharma").contactEmail("safety@globalpharma.com").contactPhone("+51-1-555-0703")
                        .build()
        );
        userRepository.saveAll(users);
        log.info("Seeded {} users", users.size());
    }

    // ─── Devices — assigned to client users ──────────────────────────────────

    /**
     * Devices represent SENSORS installed INSIDE each client's single facility.
     * location = zone/room within the facility (not a geographic address).
     * All sensors for the same client share the client's registered lat/lng.
     */
    private void seedDevices() {
        AppUser quantum = userRepository.findByEmail("ops@quantumdyn.com").orElse(null);
        AppUser harbor  = userRepository.findByEmail("facilities@harbormed.com").orElse(null);
        AppUser pharma  = userRepository.findByEmail("safety@globalpharma.com").orElse(null);

        List<Device> devices = List.of(
                // ── Quantum Dynamics Lab — Av. Universitaria 1801, San Miguel ──
                // 6 sensors inside the research facility
                Device.builder().name("Sensor EM — Lab. Física Nuclear").type("Sensor")
                        .location("Laboratorio de Física Nuclear — Piso 2")
                        .status("active").serialNumber("QD-SN-001")
                        .installDate(LocalDate.of(2025, 3, 10)).client(quantum).build(),

                Device.builder().name("Sensor EM — Sala de Investigación A").type("Sensor")
                        .location("Sala de Investigación A — Piso 1")
                        .status("active").serialNumber("QD-SN-002")
                        .installDate(LocalDate.of(2025, 3, 15)).client(quantum).build(),

                Device.builder().name("Monitor EM — Sala de Servidores").type("Monitor")
                        .location("Sala de Servidores — Sótano")
                        .status("active").serialNumber("QD-MN-001")
                        .installDate(LocalDate.of(2025, 4, 1)).client(quantum).build(),

                Device.builder().name("Sensor EM — Laboratorio Químico").type("Sensor")
                        .location("Laboratorio Químico — Piso 3")
                        .status("requires-maintenance").serialNumber("QD-SN-003")
                        .installDate(LocalDate.of(2025, 2, 20)).client(quantum).build(),

                Device.builder().name("Sensor EM — Pasillo Principal").type("Sensor")
                        .location("Pasillo Principal — Piso 1")
                        .status("active").serialNumber("QD-SN-004")
                        .installDate(LocalDate.of(2025, 4, 5)).client(quantum).build(),

                Device.builder().name("Monitor EM — Aula de Prácticas").type("Monitor")
                        .location("Aula de Prácticas — Piso 2")
                        .status("inactive").serialNumber("QD-MN-002")
                        .installDate(LocalDate.of(2025, 3, 28)).client(quantum).build(),

                // ── Harbor Medical Center — Av. Javier Prado Oeste 499, San Isidro ──
                // 7 sensors inside the clinic
                Device.builder().name("Sensor EM — Sala de Rayos X").type("Sensor")
                        .location("Sala de Rayos X — Piso 1")
                        .status("active").serialNumber("HM-SN-001")
                        .installDate(LocalDate.of(2025, 1, 20)).client(harbor).build(),

                Device.builder().name("Sensor EM — Suite de Resonancia Magnética").type("Sensor")
                        .location("Suite de Resonancia Magnética — Piso 2")
                        .status("active").serialNumber("HM-SN-002")
                        .installDate(LocalDate.of(2025, 2, 5)).client(harbor).build(),

                Device.builder().name("Monitor EM — Unidad de Radioterapia").type("Monitor")
                        .location("Unidad de Radioterapia — Piso 3")
                        .status("active").serialNumber("HM-MN-001")
                        .installDate(LocalDate.of(2025, 1, 30)).client(harbor).build(),

                Device.builder().name("Sensor EM — Sala de Urgencias").type("Sensor")
                        .location("Sala de Urgencias — Piso 1")
                        .status("active").serialNumber("HM-SN-003")
                        .installDate(LocalDate.of(2025, 4, 1)).client(harbor).build(),

                Device.builder().name("Sensor EM — Pasillo de Oncología").type("Sensor")
                        .location("Pasillo de Oncología — Piso 3")
                        .status("active").serialNumber("HM-SN-004")
                        .installDate(LocalDate.of(2025, 1, 15)).client(harbor).build(),

                Device.builder().name("Sensor EM — Sala de Espera").type("Sensor")
                        .location("Sala de Espera — Planta Baja")
                        .status("active").serialNumber("HM-SN-005")
                        .installDate(LocalDate.of(2025, 3, 10)).client(harbor).build(),

                Device.builder().name("Monitor EM — Radiología Intervencionista").type("Monitor")
                        .location("Radiología Intervencionista — Piso 2")
                        .status("requires-maintenance").serialNumber("HM-MN-002")
                        .installDate(LocalDate.of(2025, 2, 14)).client(harbor).build(),

                // ── Global Pharma Corp — Av. El Sol 455, Villa El Salvador ──
                // 6 sensors inside the industrial plant
                Device.builder().name("Sensor EM — Sala de Producción A").type("Sensor")
                        .location("Sala de Producción A — Nave Principal")
                        .status("active").serialNumber("GP-SN-001")
                        .installDate(LocalDate.of(2025, 2, 28)).client(pharma).build(),

                Device.builder().name("Sensor EM — Control de Calidad").type("Sensor")
                        .location("Laboratorio de Control de Calidad — Piso 1")
                        .status("requires-maintenance").serialNumber("GP-SN-002")
                        .installDate(LocalDate.of(2025, 3, 22)).client(pharma).build(),

                Device.builder().name("Monitor EM — Almacén de Materias Primas").type("Monitor")
                        .location("Almacén de Materias Primas — Planta Baja")
                        .status("active").serialNumber("GP-MN-001")
                        .installDate(LocalDate.of(2025, 4, 10)).client(pharma).build(),

                Device.builder().name("Sensor EM — Laboratorio Farmacéutico").type("Sensor")
                        .location("Laboratorio Farmacéutico — Piso 2")
                        .status("active").serialNumber("GP-SN-003")
                        .installDate(LocalDate.of(2025, 2, 1)).client(pharma).build(),

                Device.builder().name("Sensor EM — Área de Carga").type("Sensor")
                        .location("Área de Carga y Despacho — Planta Baja")
                        .status("active").serialNumber("GP-SN-004")
                        .installDate(LocalDate.of(2025, 1, 5)).client(pharma).build(),

                Device.builder().name("Monitor EM — Sala de Esterilización").type("Monitor")
                        .location("Sala de Esterilización — Piso 2")
                        .status("active").serialNumber("GP-MN-002")
                        .installDate(LocalDate.of(2025, 5, 1)).client(pharma).build()
        );
        deviceRepository.saveAll(devices);
        log.info("Seeded {} devices", devices.size());
    }

    /**
     * Radiation readings: ALL readings for a client share the client's registered lat/lng.
     * location = zone/room within the facility (matching the device location).
     * One reading per device (sensor), representing the latest measurement.
     */
    private void seedRadiationReadings() {
        Device qdSn1 = deviceRepository.findBySerialNumber("QD-SN-001");
        Device qdSn2 = deviceRepository.findBySerialNumber("QD-SN-002");
        Device qdMn1 = deviceRepository.findBySerialNumber("QD-MN-001");
        Device qdSn3 = deviceRepository.findBySerialNumber("QD-SN-003");
        Device qdSn4 = deviceRepository.findBySerialNumber("QD-SN-004");
        Device qdMn2 = deviceRepository.findBySerialNumber("QD-MN-002");

        Device hmSn1 = deviceRepository.findBySerialNumber("HM-SN-001");
        Device hmSn2 = deviceRepository.findBySerialNumber("HM-SN-002");
        Device hmMn1 = deviceRepository.findBySerialNumber("HM-MN-001");
        Device hmSn3 = deviceRepository.findBySerialNumber("HM-SN-003");
        Device hmSn4 = deviceRepository.findBySerialNumber("HM-SN-004");
        Device hmSn5 = deviceRepository.findBySerialNumber("HM-SN-005");
        Device hmMn2 = deviceRepository.findBySerialNumber("HM-MN-002");

        Device gpSn1 = deviceRepository.findBySerialNumber("GP-SN-001");
        Device gpSn2 = deviceRepository.findBySerialNumber("GP-SN-002");
        Device gpMn1 = deviceRepository.findBySerialNumber("GP-MN-001");
        Device gpSn3 = deviceRepository.findBySerialNumber("GP-SN-003");
        Device gpSn4 = deviceRepository.findBySerialNumber("GP-SN-004");
        Device gpMn2 = deviceRepository.findBySerialNumber("GP-MN-002");

        List<RadiationReading> readings = List.of(
            // ── Quantum Dynamics Lab — all at QD lat/lng, zone = room inside lab ──
            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.09)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Laboratorio de Física Nuclear — Piso 2")
                .sensorId("#QD-001").device(qdSn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.07)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Sala de Investigación A — Piso 1")
                .sensorId("#QD-002").device(qdSn2).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.12)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Sala de Servidores — Sótano")
                .sensorId("#QD-003").device(qdMn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.11)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Laboratorio Químico — Piso 3")
                .sensorId("#QD-004").device(qdSn3).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.06)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Pasillo Principal — Piso 1")
                .sensorId("#QD-005").device(qdSn4).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.08)
                .latitude(QD_LAT).longitude(QD_LNG)
                .location("Aula de Prácticas — Piso 2")
                .sensorId("#QD-006").device(qdMn2).build(),

            // ── Harbor Medical Center — all at HM lat/lng, zone = room inside clinic ──
            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.28)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Sala de Rayos X — Piso 1")
                .sensorId("#HM-001").device(hmSn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.35)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Suite de Resonancia Magnética — Piso 2")
                .sensorId("#HM-002").device(hmSn2).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.41)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Unidad de Radioterapia — Piso 3")
                .sensorId("#HM-003").device(hmMn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.09)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Sala de Urgencias — Piso 1")
                .sensorId("#HM-004").device(hmSn3).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.22)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Pasillo de Oncología — Piso 3")
                .sensorId("#HM-005").device(hmSn4).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.07)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Sala de Espera — Planta Baja")
                .sensorId("#HM-006").device(hmSn5).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.33)
                .latitude(HM_LAT).longitude(HM_LNG)
                .location("Radiología Intervencionista — Piso 2")
                .sensorId("#HM-007").device(hmMn2).build(),

            // ── Global Pharma Corp — all at GP lat/lng, zone = area inside plant ──
            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.18)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Sala de Producción A — Nave Principal")
                .sensorId("#GP-001").device(gpSn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.22)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Laboratorio de Control de Calidad — Piso 1")
                .sensorId("#GP-002").device(gpSn2).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.15)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Almacén de Materias Primas — Planta Baja")
                .sensorId("#GP-003").device(gpMn1).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.45)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Laboratorio Farmacéutico — Piso 2")
                .sensorId("#GP-004").device(gpSn3).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.09)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Área de Carga y Despacho — Planta Baja")
                .sensorId("#GP-005").device(gpSn4).build(),

            RadiationReading.builder().readingDate(LocalDate.of(2026, 5, 20)).value(0.38)
                .latitude(GP_LAT).longitude(GP_LNG)
                .location("Sala de Esterilización — Piso 2")
                .sensorId("#GP-006").device(gpMn2).build()
        );

        // Rescale legacy seed values (0.06–0.45) to microtesla with the linear map
        // µT = 500·v + 50 (range ~80–275 µT, preserves each reading's level), and
        // fill level/recordedAt the way the edge would have (smart-edge contract).
        LocalDateTime seedBase = LocalDate.of(2026, 5, 20).atTime(9, 0);
        int minuteOffset = 0;
        for (RadiationReading r : readings) {
            double uT = Math.round((r.getValue() * 500 + 50) * 10.0) / 10.0;
            r.setValue(uT);
            r.setLevel(RadiationLevel.byValue(uT).toUpperCase());
            r.setRecordedAt(seedBase.plusMinutes(minuteOffset));
            minuteOffset += 7;
        }
        radiationReadingRepository.saveAll(readings);
        log.info("Seeded {} radiation readings (µT scale)", readings.size());
    }

    // ─── Serie temporal de lecturas (oct–dic 2026) ────────────────────────────

    /** Primer día de la serie de demo. */
    private static final LocalDate SERIES_FROM = LocalDate.of(2026, 10, 1);
    /** Último día de la serie de demo. */
    private static final LocalDate SERIES_TO = LocalDate.of(2026, 12, 31);
    /** Ventana "actual": días con tres mediciones en lugar de una. */
    private static final LocalDate DENSE_FROM = LocalDate.of(2026, 10, 15);
    private static final LocalDate DENSE_TO = LocalDate.of(2026, 10, 28);

    /** Nivel habitual de un sensor y la sede donde está instalado. */
    private record SensorProfile(String serial, String sensorId, double baseline,
                                 double latitude, double longitude) {
    }

    /**
     * Serie diaria por sensor del 1-oct al 31-dic de 2026.
     *
     * Existe porque la telemetría real todavía no está construida y sin historia
     * no se pueden ejercitar los reportes ({@code /api/client/reports}, que agrupa
     * los últimos 30 días por día y los últimos 12 meses por mes), el promedio de
     * 24 h del dashboard ni los gráficos de tendencia. Es data de DEMO, no
     * mediciones: cuando el edge empiece a ingestar de verdad, este método sobra.
     *
     * La semilla del generador es fija a propósito — todo el equipo ve exactamente
     * los mismos números, así que un bug reportado por alguien se reproduce igual
     * en la máquina del resto.
     */
    private void seedReadingTimeSeries() {
        List<SensorProfile> profiles = List.of(
                // Quantum Dynamics Lab — laboratorio universitario, niveles bajos
                new SensorProfile("QD-SN-001", "#QD-001", 95, QD_LAT, QD_LNG),
                new SensorProfile("QD-SN-002", "#QD-002", 85, QD_LAT, QD_LNG),
                new SensorProfile("QD-MN-001", "#QD-003", 110, QD_LAT, QD_LNG),
                new SensorProfile("QD-SN-003", "#QD-004", 105, QD_LAT, QD_LNG),
                new SensorProfile("QD-SN-004", "#QD-005", 80, QD_LAT, QD_LNG),
                new SensorProfile("QD-MN-002", "#QD-006", 90, QD_LAT, QD_LNG),

                // Harbor Medical Center — clínica, picos en imagenología
                new SensorProfile("HM-SN-001", "#HM-001", 190, HM_LAT, HM_LNG),
                new SensorProfile("HM-SN-002", "#HM-002", 225, HM_LAT, HM_LNG),
                new SensorProfile("HM-MN-001", "#HM-003", 255, HM_LAT, HM_LNG),
                new SensorProfile("HM-SN-003", "#HM-004", 95, HM_LAT, HM_LNG),
                new SensorProfile("HM-SN-004", "#HM-005", 160, HM_LAT, HM_LNG),
                new SensorProfile("HM-SN-005", "#HM-006", 85, HM_LAT, HM_LNG),
                new SensorProfile("HM-MN-002", "#HM-007", 215, HM_LAT, HM_LNG),

                // Global Pharma Corp — planta industrial, los niveles más altos
                new SensorProfile("GP-SN-001", "#GP-001", 140, GP_LAT, GP_LNG),
                new SensorProfile("GP-SN-002", "#GP-002", 160, GP_LAT, GP_LNG),
                new SensorProfile("GP-MN-001", "#GP-003", 125, GP_LAT, GP_LNG),
                new SensorProfile("GP-SN-003", "#GP-004", 275, GP_LAT, GP_LNG),
                new SensorProfile("GP-SN-004", "#GP-005", 95, GP_LAT, GP_LNG),
                new SensorProfile("GP-MN-002", "#GP-006", 240, GP_LAT, GP_LNG)
        );

        Random rnd = new Random(20261028L);
        List<RadiationReading> series = new ArrayList<>();
        int sensorIndex = 0;

        for (SensorProfile p : profiles) {
            Device device = deviceRepository.findBySerialNumber(p.serial());
            if (device == null) continue;
            // Desfase por sensor: el edge no reporta los 19 al mismo minuto.
            int minuteOffset = (sensorIndex++ * 7) % 60;

            for (LocalDate day = SERIES_FROM; !day.isAfter(SERIES_TO); day = day.plusDays(1)) {
                boolean weekend = day.getDayOfWeek().getValue() >= 6;
                boolean dense = !day.isBefore(DENSE_FROM) && !day.isAfter(DENSE_TO);
                int perDay = dense ? 3 : 1;

                for (int i = 0; i < perDay; i++) {
                    double factor = 0.88 + rnd.nextDouble() * 0.24;   // ±12 % de ruido
                    if (weekend) factor *= 0.82;                      // menos actividad
                    if (rnd.nextInt(18) == 0) factor *= 1.35;         // pico esporádico
                    double uT = Math.round(p.baseline() * factor * 10.0) / 10.0;
                    String level = RadiationLevel.byValue(uT);

                    series.add(RadiationReading.builder()
                            .readingDate(day)
                            .value(uT)
                            .latitude(p.latitude()).longitude(p.longitude())
                            .location(device.getLocation())
                            .sensorId(p.sensorId())
                            .level(level.toUpperCase())
                            .message(edgeMessage(level))
                            .recordedAt(day.atTime(dense ? 7 + i * 6 : 10, 0).plusMinutes(minuteOffset))
                            .device(device)
                            .build());
                }
            }
        }

        radiationReadingRepository.saveAll(series);
        log.info("Seeded {} time-series readings ({} → {})",
                series.size(), SERIES_FROM, SERIES_TO);
    }

    /**
     * Mensaje que acompañaría la lectura si hubiera pasado por el edge. Copiado
     * literal de {@code AlertLevel} en {@code monitoring/domain/value_objects.py}
     * (sin acentos, como los manda el edge) para que la data de demo sea
     * indistinguible de la ingestada.
     */
    private static String edgeMessage(String level) {
        return switch (level) {
            case "danger" -> "Nivel critico de EMF. Se recomienda cortar la energia del equipo.";
            case "caution" -> "Exposicion moderada. Se recomienda mantener distancia de la fuente.";
            default -> "Campo dentro de rango seguro. Equipo operando con normalidad.";
        };
    }

    // ─── Work Orders ──────────────────────────────────────────────────────────

    private void seedWorkOrders() {
        AppUser marcus = userRepository.findByEmail("marcus@emsafe.com").orElse(null);
        AppUser sarah = userRepository.findByEmail("s.jenkins@field.emsafe.com").orElse(null);
        AppUser elena = userRepository.findByEmail("e.rodriguez@field.emsafe.com").orElse(null);
        AppUser maria = userRepository.findByEmail("m.silva@field.emsafe.com").orElse(null);
        AppUser robert = userRepository.findByEmail("r.king@field.emsafe.com").orElse(null);

        List<WorkOrder> adminOrders = List.of(
                buildWo("#WO-9284", WorkOrderType.INSTALLATION, WorkOrderStatus.IN_PROGRESS,
                        "Metro Logistics Corp.", "Downtown Plaza", "Austin, TX",
                        LocalDate.of(2023, 10, 24), null, sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9283", WorkOrderType.MAINTENANCE, WorkOrderStatus.IN_PROGRESS,
                        "Global Net Systems", "Tower 7 - Substation", "San Jose, CA",
                        LocalDate.of(2023, 10, 25), null, maria, "Maria Silva", "MS"),

                buildWo("#WO-9282", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Urban Health Trust", "North Wing Lab", "Seattle, WA",
                        LocalDate.of(2023, 10, 26), null, robert, "Robert King", "RK"),

                buildWo("#WO-9281", WorkOrderType.INSTALLATION, WorkOrderStatus.COMPLETED,
                        "Coastal Antenna Inc.", "Coastal Antenna 4", "Miami, FL",
                        LocalDate.of(2023, 10, 26), null, sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9280", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                        "Northwind Energy", "Building A - Floor 3", "Denver, CO",
                        LocalDate.of(2023, 10, 27), null, marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9279", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Greenfield Labs", "Sector 7", "Boston, MA",
                        LocalDate.of(2023, 10, 27), null, elena, "Elena Rodriguez", "ER"),

                buildWo("#WO-9278", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                        "Pacific Heights Clinic", "Wing B - MRI Suite", "San Francisco, CA",
                        LocalDate.of(2023, 10, 23), null, marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9277", WorkOrderType.INSTALLATION, WorkOrderStatus.COMPLETED,
                        "Harbor Medical Center", "ER Department - Ground Floor", "Portland, OR",
                        LocalDate.of(2023, 10, 22), null, elena, "Elena Rodriguez", "ER"),

                buildWo("#WO-9276", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Atlas Semiconductor", "Clean Room B4", "Phoenix, AZ",
                        LocalDate.of(2023, 10, 28), null, sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9275", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                        "Summit Data Center", "Server Hall 2", "Las Vegas, NV",
                        LocalDate.of(2023, 10, 28), null, marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9274", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                        "Nexus Research Lab", "Quantum Lab - Sub B", "Chicago, IL",
                        LocalDate.of(2023, 10, 21), null, maria, "Maria Silva", "MS"),

                buildWo("#WO-9273", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Bay General Hospital", "Radiology - 2nd Floor", "Oakland, CA",
                        LocalDate.of(2023, 10, 29), null, robert, "Robert King", "RK")
        );
        workOrderRepository.saveAll(adminOrders);

        WorkOrder wo8492 = buildDetailedWo("WO-8492", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                "TechPark Plaza", "North Wing, L4, TechPark",
                LocalDate.of(2026, 5, 11), "09:00 AM", marcus,
                "Standard", "Sarah Jenkins", "Facility Manager",
                "+1-555-0123", "s.jenkins@techpark.com",
                "Report to the security desk. Badge required.",
                6, "TP-NW-L4",
                "All sensors calibrated and within normal range.",
                List.of("Standard Toolkit"));
        wo8492.getActivityLog().add(logEntry("Work Order Completed", "May 11, 09:45", wo8492));
        wo8492.getActivityLog().add(logEntry("Arrival at Site", "May 11, 08:55", wo8492));
        workOrderRepository.save(wo8492);

        WorkOrder wo9102 = buildDetailedWo("WO-9102", WorkOrderType.INSTALLATION, WorkOrderStatus.IN_PROGRESS,
                "Greenview Residences", "Block B, Lobby, Sector 7",
                LocalDate.of(2026, 5, 12), "01:30 PM", marcus,
                "High Priority", "Linda Torres", "Property Manager",
                "+1-555-0188", "l.torres@greenview.com",
                "Ask for the building manager at the front desk.",
                6, "GV-BL-S7", "",
                List.of("Standard Toolkit", "Calibration Rig B"));
        wo9102.getSensors().add(sensor("#S-101", "Main Hallway - North Wing", "ok", wo9102));
        wo9102.getSensors().add(sensor("#S-102", "MRI Suite Entrance", "maintenance", wo9102));
        wo9102.getSensors().add(sensor("#S-103", "Emergency Exit B", "maintenance", wo9102));
        workOrderRepository.save(wo9102);

        buildAndSaveWo("WO-7734", WorkOrderType.COLLECTION, WorkOrderStatus.IN_PROGRESS,
                "St. Jude Medical", "MRI Suite A, Floor 2",
                LocalDate.of(2026, 5, 13), "10:00 AM", marcus,
                "High Priority", "Dr. Sarah Jenkins", "Department Head",
                "+1-555-0143", "s.jenkins@stjude.med",
                "Report to the security desk. Requires Level 2 RFID clearance badge.",
                8, "SJ-MRI-F2", "",
                List.of("Standard Toolkit", "Calibration Rig B"));

        buildAndSaveWo("WO-4401", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                "Skyline Towers", "Rooftop Antennas, Main St.",
                LocalDate.of(2026, 5, 14), "09:00 AM", marcus,
                "Standard", "Mike Anderson", "Building Manager",
                "+1-555-0156", "m.anderson@skyline.com",
                "Rooftop access key at security desk.",
                4, "ST-RF-001", "",
                List.of("Standard Toolkit"));

        buildAndSaveWo("WO-5521", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Metropolis Data Center", "Server Room 4, Basement",
                LocalDate.of(2026, 5, 15), "11:00 AM", marcus,
                "High Priority", "James Reilly", "IT Operations Manager",
                "+1-555-0199", "j.reilly@metropolis.com",
                "Report to the security desk. Requires Level 2 RFID clearance badge.",
                8, "MDC-SR4-B", "",
                List.of("Standard Toolkit", "Calibration Rig B"));

        buildAndSaveWo("WO-3310", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Harbor Medical Center", "ER Department, Ground Floor",
                LocalDate.of(2026, 5, 18), "08:00 AM", marcus,
                "High Priority", "Dr. Patel", "Chief Medical Officer",
                "+1-555-0201", "r.patel@harbormed.com",
                "Check in at reception, escort required to ER.",
                10, "HMC-ER-GF", "",
                List.of("Standard Toolkit", "Calibration Rig B", "Safety Meter"));

        WorkOrder wo3311 = buildDetailedWo("WO-3311", WorkOrderType.MAINTENANCE, WorkOrderStatus.IN_PROGRESS,
                "Riverside Tech Hub", "Server Corridor, Level 2",
                LocalDate.of(2026, 5, 19), "10:30 AM", marcus,
                "Standard", "Karen Voss", "Facilities Coordinator",
                "+1-555-0214", "k.voss@riverside.tech",
                "Badge access via main entrance, Level 2 key card needed.",
                5, "RTH-SC-L2",
                "Sensor 203 shows intermittent signal. Investigating.",
                List.of("Standard Toolkit"));
        wo3311.getSensors().add(sensor("#S-201", "Corridor A - Entry", "ok", wo3311));
        wo3311.getSensors().add(sensor("#S-202", "Corridor B - Middle", "ok", wo3311));
        wo3311.getSensors().add(sensor("#S-203", "Server Room Door", "maintenance", wo3311));
        workOrderRepository.save(wo3311);

        WorkOrder wo3312 = buildDetailedWo("WO-3312", WorkOrderType.COLLECTION, WorkOrderStatus.COMPLETED,
                "Downtown Financial Tower", "Executive Floor 28, Zone C",
                LocalDate.of(2026, 5, 20), "02:00 PM", marcus,
                "Standard", "Olivia Grant", "Office Manager",
                "+1-555-0222", "o.grant@dft.com",
                "Security check at lobby, appointment registered.",
                3, "DFT-EF-28C",
                "All 3 sensors collected and sealed for transport.",
                List.of("Collection Kit"));
        wo3312.getActivityLog().add(logEntry("Work Order Completed", "May 20, 14:52", wo3312));
        wo3312.getActivityLog().add(logEntry("Sensors Collected", "May 20, 14:35", wo3312));
        wo3312.getActivityLog().add(logEntry("Arrival at Site", "May 20, 14:05", wo3312));
        workOrderRepository.save(wo3312);

        buildAndSaveWo("WO-3313", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Nexus Research Lab", "Quantum Lab - Sub B",
                LocalDate.of(2026, 5, 21), "09:00 AM", marcus,
                "High Priority", "Dr. Nguyen", "Lab Director",
                "+1-555-0233", "d.nguyen@nexuslab.com",
                "Clearance required. Contact security 30 min prior.",
                12, "NRL-QL-SB", "",
                List.of("Standard Toolkit", "Calibration Rig B"));

        buildAndSaveWo("WO-3314", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                "Pacific Heights Clinic", "Wing B - MRI Suite",
                LocalDate.of(2026, 5, 22), "11:00 AM", marcus,
                "Standard", "Nurse Martinez", "Department Supervisor",
                "+1-555-0241", "c.martinez@phclinic.com",
                "Sign in at nursing station. Do not enter when MRI in use.",
                4, "PHC-WB-MRI", "",
                List.of("Standard Toolkit"));

        WorkOrder wo4420 = buildDetailedWo("WO-4420", WorkOrderType.COLLECTION, WorkOrderStatus.COMPLETED,
                "Atlas Semiconductor Plant", "Clean Room B4",
                LocalDate.of(2026, 5, 25), "08:30 AM", marcus,
                "Standard", "Thomas Holt", "Plant Manager",
                "+1-555-0255", "t.holt@atlassemi.com",
                "Full clean room protocol. Suit-up at entry.",
                6, "ASP-CR-B4",
                "Clean room protocol followed. 6 sensors collected.",
                List.of("Collection Kit", "ESD Protection"));
        wo4420.getActivityLog().add(logEntry("Work Order Completed", "May 25, 09:30", wo4420));
        wo4420.getActivityLog().add(logEntry("Arrival at Site", "May 25, 08:25", wo4420));
        workOrderRepository.save(wo4420);

        buildAndSaveWo("WO-4421", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Summit Data Center", "Server Hall 2, Rack Row D",
                LocalDate.of(2026, 5, 26), "09:00 AM", marcus,
                "High Priority", "Bryan Lee", "Data Center Manager",
                "+1-555-0266", "b.lee@summitdc.com",
                "Biometric check at entry. No phones in server hall.",
                8, "SDC-SH2-D", "",
                List.of("Standard Toolkit", "Calibration Rig B"));

        WorkOrder wo4422 = buildDetailedWo("WO-4422", WorkOrderType.MAINTENANCE, WorkOrderStatus.IN_PROGRESS,
                "Bay Area General Hospital", "Radiology - 2nd Floor",
                LocalDate.of(2026, 5, 27), "01:00 PM", marcus,
                "Standard", "Jessica Chan", "Radiology Supervisor",
                "+1-555-0277", "j.chan@bagh.com",
                "Report to radiology reception. Wear hospital ID badge.",
                5, "BAGH-RAD-2F",
                "S-303 needs recalibration. Parts ordered.",
                List.of("Standard Toolkit"));
        wo4422.getSensors().add(sensor("#S-301", "X-Ray Room 1", "ok", wo4422));
        wo4422.getSensors().add(sensor("#S-302", "CT Scan Room", "ok", wo4422));
        wo4422.getSensors().add(sensor("#S-303", "MRI Anteroom", "maintenance", wo4422));
        wo4422.getSensors().add(sensor("#S-304", "Hallway B", "ok", wo4422));
        workOrderRepository.save(wo4422);

        buildAndSaveWo("WO-4423", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                "Urban Tech Campus", "Innovation Hub - 5th Floor",
                LocalDate.of(2026, 5, 28), "03:00 PM", marcus,
                "Standard", "Sophia Reyes", "Campus Operations",
                "+1-555-0288", "s.reyes@urbantechcampus.com",
                "Check in at the main desk. Elevator B to 5th floor.",
                4, "UTC-IH-5F", "",
                List.of("Collection Kit"));

        WorkOrder wo5530 = buildDetailedWo("WO-5530", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                "Vertex Telecom Hub", "Antenna Array - Roof Level",
                LocalDate.of(2026, 5, 5), "07:00 AM", marcus,
                "Standard", "Paul Simmons", "Telecom Engineer",
                "+1-555-0299", "p.simmons@vertextelecom.com",
                "Roof key at security. Hard hat required.",
                6, "VTH-AR-RF",
                "All antennas inspected. Minor alignment done on sensor 4.",
                List.of("Standard Toolkit", "Safety Meter"));
        wo5530.getActivityLog().add(logEntry("Work Order Completed", "May 05, 09:15", wo5530));
        wo5530.getActivityLog().add(logEntry("Maintenance Performed", "May 05, 08:00", wo5530));
        wo5530.getActivityLog().add(logEntry("Arrival at Site", "May 05, 07:10", wo5530));
        workOrderRepository.save(wo5530);

        buildAndSaveWo("WO-5531", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Global Pharma HQ", "Lab Wing C - Floor 3",
                LocalDate.of(2026, 5, 8), "10:00 AM", marcus,
                "High Priority", "Dr. Amelia Stone", "Lab Safety Officer",
                "+1-555-0311", "a.stone@globalpharma.com",
                "Lab coat and goggles required. Escort from security.",
                10, "GPH-LWC-F3", "",
                List.of("Standard Toolkit", "Calibration Rig B"));

        // ── Demo orders linked to real client users (enable the tech device flows) ──
        AppUser quantum = userRepository.findByEmail("ops@quantumdyn.com").orElse(null);
        AppUser harbor  = userRepository.findByEmail("facilities@harbormed.com").orElse(null);
        AppUser pharma  = userRepository.findByEmail("safety@globalpharma.com").orElse(null);

        WorkOrder woInstall = buildDetailedWo("WO-6001", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Quantum Dynamics Lab", "Av. Universitaria 1801, San Miguel, Lima",
                LocalDate.of(2026, 6, 18), "09:00 AM", marcus,
                "Urgent", "Dr. Quantum Ops", "Lab Director",
                "+51-1-555-0701", "ops@quantumdyn.com",
                "Report to PUCP security. Escort required to the research wing.",
                4, "QD-INS-01", "",
                List.of("Standard Toolkit"));
        woInstall.setClientUser(quantum);
        workOrderRepository.save(woInstall);

        WorkOrder woMaint = buildDetailedWo("WO-6002", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                "Harbor Medical Center", "Av. Javier Prado Oeste 499, San Isidro, Lima",
                LocalDate.of(2026, 6, 19), "11:00 AM", marcus,
                "Urgent", "Facilities Harbor", "Facilities Manager",
                "+51-1-555-0702", "facilities@harbormed.com",
                "Check in at reception. Do not enter rooms while equipment is in use.",
                7, "HM-MNT-01", "",
                List.of("Standard Toolkit", "Diagnostic Toolkit"));
        woMaint.setClientUser(harbor);
        workOrderRepository.save(woMaint);

        WorkOrder woCollect = buildDetailedWo("WO-6003", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                "Global Pharma Corp", "Av. El Sol 455, Villa El Salvador, Lima",
                LocalDate.of(2026, 6, 20), "08:30 AM", marcus,
                "Standard", "Safety Pharma", "Safety Officer",
                "+51-1-555-0703", "safety@globalpharma.com",
                "Full plant protocol. Suit-up at entry.",
                6, "GP-COL-01", "",
                List.of("Standard Toolkit", "Removal Toolkit"));
        woCollect.setClientUser(pharma);
        workOrderRepository.save(woCollect);

        // ── Temporada oct–dic 2026 ───────────────────────────────────────────
        // Octubre cerrado o en curso; noviembre y diciembre programados. Las
        // ciudades repiten los sufijos ", TX/CA/WA/NY" porque el gráfico
        // regional del dashboard cuenta por ahí (countByCitySuffix).
        List<WorkOrder> seasonOrders = List.of(
                buildWo("#WO-9301", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                        "Northwind Energy", "Substation 4 - East Yard", "Austin, TX",
                        LocalDate.of(2026, 10, 2), "08:30 AM", marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9302", WorkOrderType.INSTALLATION, WorkOrderStatus.COMPLETED,
                        "Bay General Hospital", "Radiology - 2nd Floor", "Oakland, CA",
                        LocalDate.of(2026, 10, 6), "10:00 AM", sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9303", WorkOrderType.COLLECTION, WorkOrderStatus.COMPLETED,
                        "Atlas Semiconductor", "Clean Room B4", "Phoenix, AZ",
                        LocalDate.of(2026, 10, 9), "09:15 AM", elena, "Elena Rodriguez", "ER"),

                buildWo("#WO-9304", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                        "Urban Health Trust", "North Wing Lab", "Seattle, WA",
                        LocalDate.of(2026, 10, 14), "13:00 PM", maria, "Maria Silva", "MS"),

                buildWo("#WO-9305", WorkOrderType.INSTALLATION, WorkOrderStatus.IN_PROGRESS,
                        "Summit Data Center", "Server Hall 2", "Las Vegas, NV",
                        LocalDate.of(2026, 10, 20), "11:30 AM", marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9306", WorkOrderType.MAINTENANCE, WorkOrderStatus.IN_PROGRESS,
                        "Global Net Systems", "Tower 7 - Substation", "San Jose, CA",
                        LocalDate.of(2026, 10, 23), "15:00 PM", sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9307", WorkOrderType.COLLECTION, WorkOrderStatus.IN_PROGRESS,
                        "Metro Logistics Corp.", "Downtown Plaza", "Austin, TX",
                        LocalDate.of(2026, 10, 27), "08:00 AM", elena, "Elena Rodriguez", "ER"),

                buildWo("#WO-9308", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                        "Greenfield Labs", "Sector 7 - Annex", "Boston, MA",
                        LocalDate.of(2026, 11, 4), "09:00 AM", marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9309", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                        "Pacific Heights Clinic", "Wing B - MRI Suite", "San Francisco, CA",
                        LocalDate.of(2026, 11, 10), "10:30 AM", maria, "Maria Silva", "MS"),

                buildWo("#WO-9310", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Nexus Research Lab", "Quantum Lab - Sub B", "Chicago, IL",
                        LocalDate.of(2026, 11, 17), "14:00 PM", sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9311", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                        "Coastal Antenna Inc.", "Coastal Antenna 7", "Miami, FL",
                        LocalDate.of(2026, 11, 25), "08:45 AM", elena, "Elena Rodriguez", "ER"),

                buildWo("#WO-9312", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                        "Urban Health Trust", "South Wing - Oncology", "Seattle, WA",
                        LocalDate.of(2026, 12, 2), "09:30 AM", marcus, "Marcus Rivera", "MR"),

                buildWo("#WO-9313", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                        "Atlas Semiconductor", "Clean Room A1", "Phoenix, AZ",
                        LocalDate.of(2026, 12, 9), "11:00 AM", maria, "Maria Silva", "MS"),

                buildWo("#WO-9314", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                        "Bay General Hospital", "Emergency Wing - Ground", "Oakland, CA",
                        LocalDate.of(2026, 12, 15), "08:00 AM", sarah, "Sarah Jenkins", "SJ"),

                buildWo("#WO-9315", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                        "Summit Data Center", "Server Hall 1", "Las Vegas, NV",
                        LocalDate.of(2026, 12, 22), "13:30 PM", elena, "Elena Rodriguez", "ER")
        );
        workOrderRepository.saveAll(seasonOrders);

        // Órdenes detalladas de la temporada, ligadas a los clientes reales: son
        // las que alimentan la agenda del técnico (/tech) y los flujos de cliente.
        WorkOrder woOct = buildDetailedWo("WO-6101", WorkOrderType.MAINTENANCE, WorkOrderStatus.COMPLETED,
                "Quantum Dynamics Lab", "Av. Universitaria 1801, San Miguel, Lima",
                LocalDate.of(2026, 10, 8), "09:00 AM", marcus,
                "Standard", "Dr. Quantum Ops", "Lab Director",
                "+51-1-555-0701", "ops@quantumdyn.com",
                "Report to PUCP security. Escort required to the research wing.",
                6, "QD-MNT-10",
                "Calibración trimestral completada. Sensor del Lab. Químico requiere repuesto.",
                List.of("Standard Toolkit", "Calibration Rig B"));
        woOct.setClientUser(quantum);
        woOct.getActivityLog().add(logEntry("Work Order Completed", "Oct 08, 11:40", woOct));
        woOct.getActivityLog().add(logEntry("Maintenance Performed", "Oct 08, 09:30", woOct));
        woOct.getActivityLog().add(logEntry("Arrival at Site", "Oct 08, 09:05", woOct));
        workOrderRepository.save(woOct);

        WorkOrder woOctHm = buildDetailedWo("WO-6102", WorkOrderType.MAINTENANCE, WorkOrderStatus.IN_PROGRESS,
                "Harbor Medical Center", "Av. Javier Prado Oeste 499, San Isidro, Lima",
                LocalDate.of(2026, 10, 26), "11:00 AM", marcus,
                "High Priority", "Facilities Harbor", "Facilities Manager",
                "+51-1-555-0702", "facilities@harbormed.com",
                "Check in at reception. Do not enter rooms while equipment is in use.",
                7, "HM-MNT-10", "",
                List.of("Standard Toolkit", "Diagnostic Toolkit"));
        woOctHm.setClientUser(harbor);
        woOctHm.getSensors().add(sensor("#HM-002", "Suite de Resonancia Magnética — Piso 2", "maintenance", woOctHm));
        woOctHm.getSensors().add(sensor("#HM-007", "Radiología Intervencionista — Piso 2", "maintenance", woOctHm));
        woOctHm.getActivityLog().add(logEntry("Arrival at Site", "Oct 26, 11:10", woOctHm));
        workOrderRepository.save(woOctHm);

        WorkOrder woNov = buildDetailedWo("WO-6103", WorkOrderType.INSTALLATION, WorkOrderStatus.PENDING,
                "Global Pharma Corp", "Av. El Sol 455, Villa El Salvador, Lima",
                LocalDate.of(2026, 11, 12), "08:30 AM", marcus,
                "Urgent", "Safety Pharma", "Safety Officer",
                "+51-1-555-0703", "safety@globalpharma.com",
                "Full plant protocol. Suit-up at entry.",
                4, "GP-INS-11", "",
                List.of("Standard Toolkit", "Calibration Rig B"));
        woNov.setClientUser(pharma);
        workOrderRepository.save(woNov);

        WorkOrder woNovQd = buildDetailedWo("WO-6104", WorkOrderType.COLLECTION, WorkOrderStatus.PENDING,
                "Quantum Dynamics Lab", "Av. Universitaria 1801, San Miguel, Lima",
                LocalDate.of(2026, 11, 24), "14:00 PM", marcus,
                "Standard", "Dr. Quantum Ops", "Lab Director",
                "+51-1-555-0701", "ops@quantumdyn.com",
                "Retiro del monitor del Aula de Prácticas (fuera de servicio).",
                1, "QD-COL-11", "",
                List.of("Removal Toolkit"));
        woNovQd.setClientUser(quantum);
        workOrderRepository.save(woNovQd);

        WorkOrder woDic = buildDetailedWo("WO-6105", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                "Harbor Medical Center", "Av. Javier Prado Oeste 499, San Isidro, Lima",
                LocalDate.of(2026, 12, 11), "10:00 AM", marcus,
                "High Priority", "Facilities Harbor", "Facilities Manager",
                "+51-1-555-0702", "facilities@harbormed.com",
                "Revisión de cierre de año. Coordinar con el área de imagenología.",
                7, "HM-MNT-12", "",
                List.of("Standard Toolkit", "Safety Meter"));
        woDic.setClientUser(harbor);
        workOrderRepository.save(woDic);

        WorkOrder woDicGp = buildDetailedWo("WO-6106", WorkOrderType.MAINTENANCE, WorkOrderStatus.PENDING,
                "Global Pharma Corp", "Av. El Sol 455, Villa El Salvador, Lima",
                LocalDate.of(2026, 12, 19), "08:00 AM", marcus,
                "Urgent", "Safety Pharma", "Safety Officer",
                "+51-1-555-0703", "safety@globalpharma.com",
                "Lab. Farmacéutico en DANGER sostenido. Revisar blindaje de la fuente.",
                6, "GP-MNT-12", "",
                List.of("Standard Toolkit", "Safety Meter", "Calibration Rig B"));
        woDicGp.setClientUser(pharma);
        workOrderRepository.save(woDicGp);

        log.info("Seeded work orders");
    }

    // ─── Alerts ───────────────────────────────────────────────────────────────

    private void seedAlerts() {
        List<Alert> alerts = List.of(
                Alert.builder().type("danger").icon("ph-warning-circle")
                        .title("Pico de radiación anómalo — Miraflores")
                        .description("Sensor #LM-012 en Av. Larco detectó niveles de 240 µT.")
                        .relativeTime("8 min ago")
                        .createdAt(LocalDateTime.now().minusMinutes(8)).build(),

                Alert.builder().type("danger").icon("ph-warning-circle")
                        .title("Sensor fuera de línea — Hospital Rebagliati")
                        .description("Sensor #LM-007 en la Unidad de Radioterapia perdió conexión.")
                        .relativeTime("25 min ago")
                        .createdAt(LocalDateTime.now().minusMinutes(25)).build(),

                Alert.builder().type("warning").icon("ph-warning")
                        .title("Nivel en umbral — San Isidro")
                        .description("Sensor #LM-019 registra 190 µT, cerca del límite de precaución.")
                        .relativeTime("1 hour ago")
                        .createdAt(LocalDateTime.now().minusHours(1)).build(),

                Alert.builder().type("info").icon("ph-arrows-clockwise")
                        .title("Calibración automática exitosa")
                        .description("Nodo de red #LM-004 (Callao) recalibrado correctamente.")
                        .relativeTime("2 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(2)).build(),

                Alert.builder().type("success").icon("ph-check-circle")
                        .title("Orden completada — Clínica San Pablo")
                        .description("WO-LM-0021 cerrada exitosamente. 6 sensores instalados.")
                        .relativeTime("3 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(3)).build(),

                Alert.builder().type("info").icon("ph-clipboard-text")
                        .title("Nueva orden de trabajo creada")
                        .description("WO-LM-0025 asignada a Marcus Rivera — Hospital Almenara, La Victoria.")
                        .relativeTime("4 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(4)).build(),

                Alert.builder().type("success").icon("ph-user-plus")
                        .title("Nuevo técnico incorporado")
                        .description("Carlos Mendoza agregado al equipo de operaciones Lima Sur.")
                        .relativeTime("6 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(6)).build(),

                Alert.builder().type("danger").icon("ph-warning-circle")
                        .title("Nivel crítico — Villa El Salvador")
                        .description("Sensor #LM-023 cerca de planta industrial registró 275 µT.")
                        .relativeTime("8 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(8)).build(),

                Alert.builder().type("warning").icon("ph-wifi-slash")
                        .title("Conectividad degradada — Los Olivos")
                        .description("Gateway #GW-LM-03 reporta pérdida de paquetes del 18%.")
                        .relativeTime("10 hours ago")
                        .createdAt(LocalDateTime.now().minusHours(10)).build(),

                Alert.builder().type("info").icon("ph-shield-check")
                        .title("Auditoría mensual completada")
                        .description("Todos los sensores de la zona Centro Histórico superaron la revisión.")
                        .relativeTime("1 day ago")
                        .createdAt(LocalDateTime.now().minusDays(1)).build()
        );
        alertRepository.saveAll(alerts);

        // ── Temporada oct–dic 2026 ───────────────────────────────────────────
        // Fechas FIJAS (las de arriba son relativas a now()), con destinatario y
        // sensor poblados para que el modal de detalle tenga qué mostrar. Varias
        // van resueltas: así la fila de estado y el botón de resolver se pueden
        // probar en los dos casos.
        AppUser quantumUser = userRepository.findByEmail("ops@quantumdyn.com").orElse(null);
        AppUser harborUser = userRepository.findByEmail("facilities@harbormed.com").orElse(null);
        AppUser pharmaUser = userRepository.findByEmail("safety@globalpharma.com").orElse(null);

        List<Alert> seasonAlerts = List.of(
                seasonAlert("danger", "ph-warning-circle",
                        "Nivel crítico sostenido — Global Pharma Corp",
                        "El sensor #GP-004 (Laboratorio Farmacéutico) superó 270 µT durante 3 h.",
                        LocalDateTime.of(2026, 10, 3, 9, 20), "#GP-004", pharmaUser,
                        LocalDateTime.of(2026, 10, 3, 14, 5)),

                seasonAlert("warning", "ph-warning",
                        "Nivel en umbral — Harbor Medical Center",
                        "El sensor #HM-005 (Pasillo de Oncología) ronda los 160 µT.",
                        LocalDateTime.of(2026, 10, 7, 11, 45), "#HM-005", harborUser,
                        LocalDateTime.of(2026, 10, 8, 10, 0)),

                seasonAlert("danger", "ph-warning-circle",
                        "Pico en resonancia magnética — Harbor Medical Center",
                        "El sensor #HM-002 registró 248 µT durante una sesión de imagenología.",
                        LocalDateTime.of(2026, 10, 12, 16, 30), "#HM-002", harborUser, null),

                seasonAlert("info", "ph-arrows-clockwise",
                        "Calibración trimestral completada — Quantum Dynamics Lab",
                        "Los 6 sensores de la sede fueron recalibrados en la orden WO-6101.",
                        LocalDateTime.of(2026, 10, 8, 11, 40), "#QD-001", quantumUser,
                        LocalDateTime.of(2026, 10, 8, 11, 40)),

                seasonAlert("warning", "ph-wrench",
                        "Sensor requiere mantenimiento — Quantum Dynamics Lab",
                        "El sensor #QD-004 (Laboratorio Químico) reporta deriva en la medición.",
                        LocalDateTime.of(2026, 10, 18, 8, 15), "#QD-004", quantumUser, null),

                seasonAlert("danger", "ph-warning-circle",
                        "Nivel crítico — Global Pharma Corp",
                        "El sensor #GP-006 (Sala de Esterilización) alcanzó 252 µT.",
                        LocalDateTime.of(2026, 10, 22, 13, 50), "#GP-006", pharmaUser, null),

                seasonAlert("warning", "ph-wifi-slash",
                        "Conectividad degradada — Harbor Medical Center",
                        "El monitor #HM-007 perdió 12 % de los paquetes en la última hora.",
                        LocalDateTime.of(2026, 10, 26, 11, 25), "#HM-007", harborUser, null),

                seasonAlert("info", "ph-clipboard-text",
                        "Orden de instalación programada — Global Pharma Corp",
                        "WO-6103 asignada a Marcus Rivera para el 12 de noviembre.",
                        LocalDateTime.of(2026, 10, 27, 17, 0), null, pharmaUser, null),

                seasonAlert("warning", "ph-warning",
                        "Revisión de blindaje pendiente — Global Pharma Corp",
                        "El Lab. Farmacéutico acumula 14 días en DANGER. Orden WO-6106 creada.",
                        LocalDateTime.of(2026, 11, 6, 9, 0), "#GP-004", pharmaUser, null),

                seasonAlert("info", "ph-shield-check",
                        "Auditoría mensual completada — noviembre",
                        "Las tres sedes superaron la revisión mensual de cumplimiento.",
                        LocalDateTime.of(2026, 11, 28, 18, 30), null, null, null),

                seasonAlert("info", "ph-calendar-check",
                        "Cierre de año programado",
                        "Revisiones de diciembre agendadas en las tres sedes (WO-6105, WO-6106).",
                        LocalDateTime.of(2026, 12, 1, 8, 0), null, null, null),

                seasonAlert("success", "ph-check-circle",
                        "Mantenimiento de cierre completado — Harbor Medical Center",
                        "WO-6105 cerrada. 7 sensores revisados, ninguno fuera de rango.",
                        LocalDateTime.of(2026, 12, 11, 13, 15), null, harborUser,
                        LocalDateTime.of(2026, 12, 11, 13, 15))
        );
        alertRepository.saveAll(seasonAlerts);
        log.info("Seeded {} alerts ({} base + {} oct–dic)",
                alerts.size() + seasonAlerts.size(), alerts.size(), seasonAlerts.size());
    }

    /**
     * Alarma de la temporada oct–dic con fecha fija. Cuando {@code client} viene
     * null la alarma va dirigida a todos; {@code resolvedAt} no null la deja ya
     * atendida.
     */
    private Alert seasonAlert(String type, String icon, String title, String description,
                              LocalDateTime createdAt, String sensor, AppUser client,
                              LocalDateTime resolvedAt) {
        Alert alert = Alert.builder()
                .type(type).icon(icon)
                .title(title).description(description)
                .sensor(sensor)
                .createdAt(createdAt)
                .resolved(resolvedAt != null)
                .resolvedAt(resolvedAt)
                .build();
        if (client != null) {
            alert.addressTo(List.of(client.getId()), client.getName());
        } else {
            alert.addressToEveryone();
        }
        return alert;
    }

    // ─── History ──────────────────────────────────────────────────────────────

    private void seedHistory() {
        AppUser marcus = userRepository.findByEmail("marcus@emsafe.com").orElse(null);
        AppUser sarah = userRepository.findByEmail("s.jenkins@field.emsafe.com").orElse(null);
        AppUser elena = userRepository.findByEmail("e.rodriguez@field.emsafe.com").orElse(null);

        Long marcusId = marcus != null ? marcus.getId() : null;
        Long sarahId = sarah != null ? sarah.getId() : null;
        Long elenaId = elena != null ? elena.getId() : null;

        List<History> records = List.of(
                hist("#WO-LM-0021", LocalDate.of(2026, 5, 25), "14:30 PM",
                        "Clínica San Pablo", "Av. Javier Prado Este 499, San Borja",
                        "Installation", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0020", LocalDate.of(2026, 5, 23), "10:00 AM",
                        "Hospital Almenara — ESSALUD", "Av. Grau 800, La Victoria",
                        "Maintenance", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-LM-0019", LocalDate.of(2026, 5, 21), "09:15 AM",
                        "Terminal Portuario Callao", "Av. Óscar R. Benavides, Callao",
                        "Collection", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-LM-0018", LocalDate.of(2026, 5, 19), "15:45 PM",
                        "Jockey Plaza Shopping Center", "Av. Jockey, Santiago de Surco",
                        "Installation", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0017", LocalDate.of(2026, 5, 17), "08:30 AM",
                        "Parque Industrial Villa El Salvador", "Av. El Sol s/n, VES",
                        "Maintenance", "Sarah Jenkins", "SJ", "cancelled", sarahId),
                hist("#WO-LM-0016", LocalDate.of(2026, 5, 15), "13:00 PM",
                        "PUCP — Pontificia Universidad Católica", "Av. Universitaria 1801, San Miguel",
                        "Installation", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-LM-0015", LocalDate.of(2026, 5, 13), "11:20 AM",
                        "Municipalidad de Miraflores", "Av. Larco 400, Miraflores",
                        "Collection", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0014", LocalDate.of(2026, 5, 11), "16:00 PM",
                        "Centro Empresarial San Isidro", "Calle Las Begonias 441, San Isidro",
                        "Maintenance", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-LM-0013", LocalDate.of(2026, 5, 9), "09:45 AM",
                        "Plaza Norte Centro Comercial", "Av. Alfredo Mendiola 1400, Los Olivos",
                        "Installation", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-LM-0012", LocalDate.of(2026, 5, 7), "14:10 PM",
                        "Zona Industrial Ate Vitarte", "Carretera Central Km 5, Ate",
                        "Collection", "Marcus Rivera", "MR", "cancelled", marcusId),
                hist("#WO-LM-0011", LocalDate.of(2026, 4, 28), "10:30 AM",
                        "Hospital Rebagliati — ESSALUD", "Av. Edgardo Rebagliati 490, Jesús María",
                        "Maintenance", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-LM-0010", LocalDate.of(2026, 4, 25), "08:00 AM",
                        "Clínica Internacional San Isidro", "Av. Salaverry 2020, San Isidro",
                        "Installation", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0009", LocalDate.of(2026, 4, 22), "13:30 PM",
                        "Canto Grande — Colegio San Juan", "Av. Próceres 1500, SJL",
                        "Collection", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-LM-0008", LocalDate.of(2026, 4, 19), "15:00 PM",
                        "Gran Teatro Nacional", "Av. De la Poesía 121, San Borja",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0007", LocalDate.of(2026, 4, 16), "09:00 AM",
                        "Autoridad Portuaria Nacional", "Jr. Contralmirante Mora 204, Callao",
                        "Installation", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-LM-0006", LocalDate.of(2026, 4, 13), "11:00 AM",
                        "Ministerio de Salud — Sede Central", "Av. Salaverry 801, Jesús María",
                        "Maintenance", "Elena Rodriguez", "ER", "cancelled", elenaId),
                hist("#WO-LM-0005", LocalDate.of(2026, 4, 10), "14:45 PM",
                        "Playa La Herradura, Chorrillos", "Av. La Herradura s/n, Chorrillos",
                        "Collection", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0004", LocalDate.of(2026, 4, 7), "10:15 AM",
                        "Estadio Nacional del Perú", "Jr. José Díaz s/n, Cercado de Lima",
                        "Installation", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-LM-0003", LocalDate.of(2026, 4, 4), "08:30 AM",
                        "Aeropuerto Jorge Chávez", "Av. Elmer Faucett s/n, Callao",
                        "Maintenance", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-LM-0002", LocalDate.of(2026, 4, 1), "13:00 PM",
                        "Universidad Nacional Mayor de San Marcos", "Ciudad Universitaria, Cercado de Lima",
                        "Collection", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-LM-0001", LocalDate.of(2026, 3, 28), "15:30 PM",
                        "Refinería La Pampilla", "Km 25 Panamericana Norte, Ventanilla",
                        "Installation", "Sarah Jenkins", "SJ", "cancelled", sarahId)
        );
        historyRepository.saveAll(records);

        // ── Temporada oct–dic 2026 ───────────────────────────────────────────
        // Los orderId repiten los de las órdenes cerradas de seedWorkOrders para
        // que el historial y la lista de órdenes cuenten lo mismo.
        List<History> seasonRecords = List.of(
                hist("#WO-9301", LocalDate.of(2026, 10, 2), "08:30 AM",
                        "Northwind Energy", "Substation 4 - East Yard, Austin TX",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9302", LocalDate.of(2026, 10, 6), "10:00 AM",
                        "Bay General Hospital", "Radiology - 2nd Floor, Oakland CA",
                        "Installation", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-6101", LocalDate.of(2026, 10, 8), "09:00 AM",
                        "Quantum Dynamics Lab", "Av. Universitaria 1801, San Miguel",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9303", LocalDate.of(2026, 10, 9), "09:15 AM",
                        "Atlas Semiconductor", "Clean Room B4, Phoenix AZ",
                        "Collection", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-9304", LocalDate.of(2026, 10, 14), "13:00 PM",
                        "Urban Health Trust", "North Wing Lab, Seattle WA",
                        "Maintenance", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-9298", LocalDate.of(2026, 10, 17), "15:30 PM",
                        "Terminal Portuario Callao", "Av. Óscar R. Benavides, Callao",
                        "Collection", "Elena Rodriguez", "ER", "cancelled", elenaId),
                hist("#WO-9299", LocalDate.of(2026, 10, 21), "11:00 AM",
                        "Jockey Plaza Shopping Center", "Av. Jockey, Santiago de Surco",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),

                hist("#WO-9308", LocalDate.of(2026, 11, 4), "09:00 AM",
                        "Greenfield Labs", "Sector 7 - Annex, Boston MA",
                        "Installation", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9309", LocalDate.of(2026, 11, 10), "10:30 AM",
                        "Pacific Heights Clinic", "Wing B - MRI Suite, San Francisco CA",
                        "Maintenance", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-6103", LocalDate.of(2026, 11, 12), "08:30 AM",
                        "Global Pharma Corp", "Av. El Sol 455, Villa El Salvador",
                        "Installation", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9310", LocalDate.of(2026, 11, 17), "14:00 PM",
                        "Nexus Research Lab", "Quantum Lab - Sub B, Chicago IL",
                        "Collection", "Elena Rodriguez", "ER", "cancelled", elenaId),
                hist("#WO-6104", LocalDate.of(2026, 11, 24), "14:00 PM",
                        "Quantum Dynamics Lab", "Av. Universitaria 1801, San Miguel",
                        "Collection", "Marcus Rivera", "MR", "completed", marcusId),

                hist("#WO-9312", LocalDate.of(2026, 12, 2), "09:30 AM",
                        "Urban Health Trust", "South Wing - Oncology, Seattle WA",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9313", LocalDate.of(2026, 12, 9), "11:00 AM",
                        "Atlas Semiconductor", "Clean Room A1, Phoenix AZ",
                        "Collection", "Sarah Jenkins", "SJ", "completed", sarahId),
                hist("#WO-6105", LocalDate.of(2026, 12, 11), "10:00 AM",
                        "Harbor Medical Center", "Av. Javier Prado Oeste 499, San Isidro",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId),
                hist("#WO-9314", LocalDate.of(2026, 12, 15), "08:00 AM",
                        "Bay General Hospital", "Emergency Wing - Ground, Oakland CA",
                        "Installation", "Elena Rodriguez", "ER", "completed", elenaId),
                hist("#WO-6106", LocalDate.of(2026, 12, 19), "08:00 AM",
                        "Global Pharma Corp", "Av. El Sol 455, Villa El Salvador",
                        "Maintenance", "Marcus Rivera", "MR", "completed", marcusId)
        );
        historyRepository.saveAll(seasonRecords);
        log.info("Seeded {} history records ({} base + {} oct–dic)",
                records.size() + seasonRecords.size(), records.size(), seasonRecords.size());
    }

    // ─── Builder helpers ──────────────────────────────────────────────────────

    private WorkOrder buildWo(String orderId, WorkOrderType type, WorkOrderStatus status,
                              String client, String location, String city, LocalDate date,
                              String time, AppUser technician, String techName, String techInitials) {
        return WorkOrder.builder()
                .orderId(orderId).type(type).status(status)
                .client(client).location(location).city(city)
                .scheduledDate(date).scheduledTime(time)
                .technician(technician)
                .technicianName(techName).technicianInitials(techInitials)
                .build();
    }

    private WorkOrder buildDetailedWo(String orderId, WorkOrderType type, WorkOrderStatus status,
                                       String client, String location, LocalDate date, String time,
                                       AppUser technician, String priority,
                                       String contactName, String contactRole,
                                       String contactPhone, String contactEmail,
                                       String accessInstructions, int expectedSensors,
                                       String assetId, String notes, List<String> tools) {
        WorkOrder wo = WorkOrder.builder()
                .orderId(orderId).type(type).status(status)
                .client(client).location(location)
                .scheduledDate(date).scheduledTime(time)
                .technician(technician)
                .technicianName(technician != null ? technician.getName() : null)
                .technicianInitials(technician != null ? technician.getInitials() : null)
                .priority(priority)
                .contactName(contactName).contactRole(contactRole)
                .contactPhone(contactPhone).contactEmail(contactEmail)
                .accessInstructions(accessInstructions)
                .expectedSensors(expectedSensors).assetId(assetId)
                .technicianNotes(notes)
                .build();
        wo.getRequiredTools().addAll(tools);
        return wo;
    }

    private void buildAndSaveWo(String orderId, WorkOrderType type, WorkOrderStatus status,
                                 String client, String location, LocalDate date, String time,
                                 AppUser technician, String priority,
                                 String contactName, String contactRole,
                                 String contactPhone, String contactEmail,
                                 String accessInstructions, int expectedSensors,
                                 String assetId, String notes, List<String> tools) {
        workOrderRepository.save(buildDetailedWo(orderId, type, status, client, location,
                date, time, technician, priority, contactName, contactRole,
                contactPhone, contactEmail, accessInstructions, expectedSensors,
                assetId, notes, tools));
    }

    private Sensor sensor(String sensorId, String location, String status, WorkOrder wo) {
        return Sensor.builder()
                .sensorId(sensorId).location(location).status(status).workOrder(wo).build();
    }

    private ActivityLogEntry logEntry(String event, String time, WorkOrder wo) {
        return ActivityLogEntry.builder()
                .event(event).logTime(time).workOrder(wo).build();
    }

    private History hist(String orderId, LocalDate date, String time,
                          String client, String site, String serviceType,
                          String technician, String initials, String status, Long technicianId) {
        return History.builder()
                .orderId(orderId).completionDate(date).completionTime(time)
                .client(client).site(site).serviceType(serviceType)
                .technician(technician).technicianInitials(initials)
                .status(status).technicianId(technicianId)
                .build();
    }
}
