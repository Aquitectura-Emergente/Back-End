package com.emsafe.devicemanagement.domain.model;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.events.DeviceConnectivityChanged;
import com.emsafe.devicemanagement.domain.events.DeviceDomainEvent;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.events.PlugCommandRequested;
import com.emsafe.devicemanagement.domain.events.PlugStateReported;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.shared.exception.BadRequestException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceTest {
    static final Instant T0 = Instant.parse("2026-11-12T15:00:00Z");
    static final AppUser QUANTUM = AppUser.builder().id(7L).email("ops@quantumdyn.com").name("Quantum").build();

    static Device dosimeter() {
        Device d = Device.register(DeviceSerial.of("EMSafe-DOS-01"), DeviceType.PERSONAL_DOSIMETER, null, QUANTUM,
                "admin", T0);
        d.pullAuditEntries();
        d.pullConfigVersions();
        d.pullEvents();
        return d;
    }

    static Device plug() {
        Device d = Device.register(DeviceSerial.of("EMSafe-PLUG-01"), DeviceType.SMART_PLUG, null, QUANTUM,
                "admin", T0);
        d.pullAuditEntries();
        d.pullEvents();
        return d;
    }

    @Nested
    class Registration {
        @Test
        void aDosimeterIsAuditedVersionedAndPublishesItsInitialConfig() {
            Device d = Device.register(DeviceSerial.of("EMSafe-DOS-01"), DeviceType.PERSONAL_DOSIMETER, null,
                    QUANTUM, "admin", T0);
            assertThat(d.getLifecycleStatus()).isEqualTo(DeviceLifecycleStatus.INSTALLED);
            assertThat(d.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
            assertThat(d.organizationId()).isEqualTo(7L);
            assertThat(d.pullAuditEntries()).extracting(DeviceAuditEntry::getEntryType)
                    .containsExactly(AuditEntryType.REGISTERED);
            assertThat(d.pullConfigVersions()).singleElement()
                    .satisfies(v -> assertThat(v.getConfigVersion()).isEqualTo(1));
            DeviceConfigChanged config = (DeviceConfigChanged) d.pullEvents().get(0);
            assertThat(config.configVersion()).isEqualTo(1);
            assertThat(config.alertThresholdUT()).isEqualByComparingTo("300");
            assertThat(config.definedBy()).isEqualTo(ConfigDefinedBy.SYSTEM_DEFAULT);
        }

        @Test
        void withoutOwnerItStaysInStockAndASensorPublishesNoConfig() {
            Device d = Device.register(DeviceSerial.of("S-1"), DeviceType.FIXED_SENSOR, null, null, "admin", T0);
            assertThat(d.getLifecycleStatus()).isEqualTo(DeviceLifecycleStatus.IN_STOCK);
            assertThat(d.isUnregistered()).isTrue();
            assertThat(d.pullEvents()).isEmpty();
        }

        @Test
        void aPlugStartsUnknown() {
            assertThat(plug().getPlugPower()).isEqualTo(PlugPower.UNKNOWN);
        }
    }

    @Nested
    class Configuration {
        @Test
        void changingTheThresholdCreatesANewVersionWithOldAndNewValuesAudited() {
            Device d = dosimeter();
            d.changeAlertThreshold(AlertThreshold.of(250), "sso@quantumdyn.com", T0.plusSeconds(60));

            assertThat(d.config().version()).isEqualTo(2);
            assertThat(d.config().definedBy()).isEqualTo(ConfigDefinedBy.SSO_RESPONSIBLE);
            DeviceAuditEntry entry = d.pullAuditEntries().get(0);
            assertThat(entry.getEntryType()).isEqualTo(AuditEntryType.CONFIG_CHANGED);
            assertThat(entry.getOldValue()).isEqualTo("300.0");
            assertThat(entry.getNewValue()).isEqualTo("250.0");
            assertThat(entry.getActor()).isEqualTo("sso@quantumdyn.com");
            assertThat(d.pullConfigVersions()).singleElement()
                    .satisfies(v -> assertThat(v.getConfigVersion()).isEqualTo(2));
            assertThat(d.pullEvents()).singleElement().isInstanceOf(DeviceConfigChanged.class);
        }

        @Test
        void theSameThresholdTwiceDoesNotCreateAVersion() {
            Device d = dosimeter();
            d.changeAlertThreshold(AlertThreshold.of(250), "sso", T0);
            d.pullEvents();
            d.changeAlertThreshold(new AlertThreshold(new BigDecimal("250.00")), "sso", T0);
            assertThat(d.config().version()).isEqualTo(2);
            assertThat(d.pullEvents()).isEmpty();
        }

        @Test
        void onlyDosimetersHaveAThreshold() {
            Device sensor = Device.register(DeviceSerial.of("S-1"), DeviceType.FIXED_SENSOR, null, QUANTUM, "a", T0);
            assertThatThrownBy(() -> sensor.changeAlertThreshold(AlertThreshold.of(100), "sso", T0))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void operationalConfigAuditsOnlyWhatChanged() {
            Device d = dosimeter();
            d.changeOperationalConfig(new SamplingInterval(2000), null, "admin", T0);
            assertThat(d.config().samplingInterval().millis()).isEqualTo(2000);
            assertThat(d.config().inactivityTimeout()).isEqualTo(InactivityTimeout.DEFAULT);
            assertThat(d.pullAuditEntries()).extracting(DeviceAuditEntry::getFieldName)
                    .containsExactly("samplingIntervalMs");
            d.changeOperationalConfig(new SamplingInterval(2000), InactivityTimeout.DEFAULT, "admin", T0);
            assertThat(d.pullAuditEntries()).isEmpty();
        }

        @Test
        void aRetiredDeviceCannotBeReconfigured() {
            Device d = dosimeter();
            d.changeLifecycle(DeviceLifecycleStatus.RETIRED, "admin", T0);
            assertThatThrownBy(() -> d.changeAlertThreshold(AlertThreshold.of(100), "sso", T0))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void retiredIsTerminal() {
            Device d = dosimeter();
            d.changeLifecycle(DeviceLifecycleStatus.RETIRED, "admin", T0);
            assertThat(d.pullAuditEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getNewValue()).isEqualTo("RETIRED"));
            assertThatThrownBy(() -> d.changeLifecycle(DeviceLifecycleStatus.INSTALLED, "admin", T0))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void releasingToThePoolUnlinksAndReturnsToStock() {
            Device d = dosimeter();
            d.releaseToPool();
            assertThat(d.getLifecycleStatus()).isEqualTo(DeviceLifecycleStatus.IN_STOCK);
            assertThat(d.organizationId()).isNull();
            assertThat(d.pullAuditEntries()).extracting(DeviceAuditEntry::getEntryType)
                    .containsExactly(AuditEntryType.LINKED, AuditEntryType.LIFECYCLE_CHANGED);
        }

        @Test
        void zoneChangesAreAudited() {
            Device d = dosimeter();
            d.assignToZone(4L, "plant", T0);
            d.assignToZone(4L, "plant", T0);
            assertThat(d.pullAuditEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getEntryType()).isEqualTo(AuditEntryType.ZONE_CHANGED));
        }
    }

    @Nested
    class Connectivity {
        @Test
        void goesOnlineAndIgnoresOlderOrRepeatedStates() {
            Device d = dosimeter();
            assertThat(d.recordConnectivity(ConnectivityStatus.ONLINE, T0, "EDGE-PLANTA-01", "STATUS", T0)).isTrue();
            assertThat(d.recordConnectivity(ConnectivityStatus.OFFLINE, T0.minusSeconds(5), "EDGE-PLANTA-01",
                    "STATUS", T0)).isFalse();
            assertThat(d.recordConnectivity(ConnectivityStatus.ONLINE, T0.plusSeconds(5), "EDGE-PLANTA-01",
                    "STATUS", T0)).isFalse();
            assertThat(d.getConnectivity()).isEqualTo(ConnectivityStatus.ONLINE);
            List<DeviceDomainEvent> events = d.pullEvents();
            assertThat(events).singleElement().isInstanceOf(DeviceConnectivityChanged.class);
            assertThat(d.pullAuditEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getActor()).isEqualTo("edge:EDGE-PLANTA-01"));
        }

        @Test
        void inactivityTakesAnOnlineDeviceOffline() {
            Device d = dosimeter();
            d.recordConnectivity(ConnectivityStatus.ONLINE, T0, "E", "STATUS", T0);
            d.markSeen(T0.plusSeconds(100), "E", T0);
            d.pullEvents();
            assertThat(d.detectInactivity(T0.plusSeconds(399))).isFalse();
            assertThat(d.detectInactivity(T0.plusSeconds(401))).isTrue();
            assertThat(d.getConnectivity()).isEqualTo(ConnectivityStatus.OFFLINE);
            assertThat(d.pullEvents()).hasSize(2).last().isInstanceOf(DeviceInactivityDetected.class);
        }

        @Test
        void aReadingBringsAnOfflineDeviceBack() {
            Device d = dosimeter();
            d.markSeen(T0, "E", T0);
            assertThat(d.getConnectivity()).isEqualTo(ConnectivityStatus.ONLINE);
            assertThat(d.getLastSeenAt()).isEqualTo(T0);
        }
    }

    @Nested
    class Telemetry {
        @Test
        void aLowerSequenceMeansANewBoot() {
            Device d = dosimeter();
            int[] n = {0};
            BootId a = d.bootFor(10, () -> new BootId("b" + ++n[0]));
            BootId b = d.bootFor(11, () -> new BootId("b" + ++n[0]));
            BootId c = d.bootFor(11, () -> new BootId("b" + ++n[0]));
            BootId reboot = d.bootFor(0, () -> new BootId("b" + ++n[0]));
            assertThat(a).isEqualTo(b).isEqualTo(c);
            assertThat(reboot).isNotEqualTo(a);
            assertThat(d.getLastSequence()).isZero();
        }

        @Test
        void aTypeMismatchIsAuditedOnceAndReported() {
            Device d = dosimeter();
            assertThat(d.noteReportedType(DeviceType.FIXED_SENSOR, ReadingAttribution.OWNER, 7L, "EDGE-HOGAR-01", T0))
                    .isTrue();
            assertThat(d.noteReportedType(DeviceType.FIXED_SENSOR, ReadingAttribution.OWNER, 7L, "EDGE-HOGAR-01", T0))
                    .isTrue();
            assertThat(d.pullAuditEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getEntryType()).isEqualTo(AuditEntryType.DEVICE_TYPE_MISMATCH));
            assertThat(d.noteReportedType(DeviceType.PERSONAL_DOSIMETER, ReadingAttribution.OWNER, 7L, "E", T0))
                    .isFalse();
            assertThat(d.pullAuditEntries()).isEmpty();
        }

        @Test
        void lendingNeedsTheFlagTheEdgeOfTheMessageOrganizationAndAnotherType() {
            Device d = dosimeter();
            assertThat(d.attributionFor(7L, 7L, DeviceType.PERSONAL_DOSIMETER, false)).isEqualTo(ReadingAttribution.OWNER);
            assertThat(d.attributionFor(9L, 9L, DeviceType.FIXED_SENSOR, true)).isEqualTo(ReadingAttribution.LENT);
            assertThat(d.attributionFor(9L, 9L, DeviceType.FIXED_SENSOR, false)).isEqualTo(ReadingAttribution.REJECTED);
            assertThat(d.attributionFor(9L, 9L, DeviceType.PERSONAL_DOSIMETER, true)).isEqualTo(ReadingAttribution.REJECTED);
            assertThat(d.attributionFor(9L, 7L, DeviceType.FIXED_SENSOR, true)).isEqualTo(ReadingAttribution.REJECTED);
            assertThat(d.attributionFor(9L, null, DeviceType.FIXED_SENSOR, true)).isEqualTo(ReadingAttribution.REJECTED);
        }
    }

    @Nested
    class Plug {
        @Test
        void aCommandIsAuditedAndRaisesAnEvent() {
            Device p = plug();
            PlugCommand c = p.requestPlugCommand(PlugAction.OFF, 3L, "user@x", T0.plusMillis(700));
            assertThat(c.getStatus()).isEqualTo(PlugCommandStatus.PENDING);
            assertThat(c.getRequestedAt()).isEqualTo(T0);
            assertThat(c.getCommandId()).hasSize(36);
            assertThat(p.pullAuditEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getEntryType()).isEqualTo(AuditEntryType.PLUG_COMMAND));
            assertThat(p.pullEvents()).singleElement().isInstanceOf(PlugCommandRequested.class);
        }

        @Test
        void onlyAPlugTakesCommands() {
            assertThatThrownBy(() -> dosimeter().requestPlugCommand(PlugAction.ON, 1L, "u", T0))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void theRealStateIgnoresRepeatsAndOlderReports() {
            Device p = plug();
            PlugState off = new PlugState(PlugPower.OFF, "c-1", PlugStateSource.USER, T0.plusSeconds(1));
            assertThat(p.reportPlugState(off, T0)).isTrue();
            assertThat(p.reportPlugState(off, T0)).isFalse();
            assertThat(p.reportPlugState(new PlugState(PlugPower.ON, null, PlugStateSource.AUTO, T0), T0)).isFalse();
            assertThat(p.plugState()).isEqualTo(off);
            assertThat(p.pullEvents()).singleElement().isInstanceOf(PlugStateReported.class);
        }

        @Test
        void aCommandResolvesWithTheReportedState() {
            Device p = plug();
            PlugCommand c = p.requestPlugCommand(PlugAction.OFF, 3L, "u", T0);
            c.resolveWith(new PlugState(PlugPower.ON, c.getCommandId(), PlugStateSource.USER, T0), T0);
            assertThat(c.getStatus()).isEqualTo(PlugCommandStatus.NOT_APPLIED);

            PlugCommand failed = p.requestPlugCommand(PlugAction.ON, 3L, "u", T0);
            failed.markSendFailed(T0);
            failed.resolveWith(new PlugState(PlugPower.ON, failed.getCommandId(), PlugStateSource.USER, T0), T0);
            assertThat(failed.getStatus()).isEqualTo(PlugCommandStatus.CONFIRMED);
        }
    }
}
