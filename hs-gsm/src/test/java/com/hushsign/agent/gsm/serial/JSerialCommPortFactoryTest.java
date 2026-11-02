package com.hushsign.agent.gsm.serial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JSerialCommPortFactoryTest {

    @Test
    void resolvesAByIdLinkToTheDeviceTheKernelHasNow(@TempDir Path dir) throws IOException {
        Path device = Files.createFile(dir.resolve("ttyUSB1"));
        Path link = Files.createSymbolicLink(dir.resolve("usb-HUAWEI_Technology_HUAWEI_Mobile-if00-port0"), device);

        assertEquals(device.toString(), JSerialCommPortFactory.resolveCurrentDevice(link.toString()));
    }

    @Test
    void followsTheLinkWhenTheDeviceRenumbers(@TempDir Path dir) throws IOException {
        Path first = Files.createFile(dir.resolve("ttyUSB0"));
        Path link = Files.createSymbolicLink(dir.resolve("usb-HUAWEI_Technology_HUAWEI_Mobile-if00-port0"), first);
        assertEquals(first.toString(), JSerialCommPortFactory.resolveCurrentDevice(link.toString()));

        Path second = Files.createFile(dir.resolve("ttyUSB1"));
        Files.delete(link);
        Files.createSymbolicLink(link, second);

        assertEquals(second.toString(), JSerialCommPortFactory.resolveCurrentDevice(link.toString()),
                "a replug must be followed to the new device node");
    }

    @Test
    void leavesAPlainDeviceNameAlone(@TempDir Path dir) throws IOException {
        Path device = Files.createFile(dir.resolve("ttyUSB2"));

        assertEquals(device.toString(), JSerialCommPortFactory.resolveCurrentDevice(device.toString()));
        assertEquals("COM3", JSerialCommPortFactory.resolveCurrentDevice("COM3"));
    }

    @Test
    void keepsTheConfiguredNameWhenTheLinkDangles(@TempDir Path dir) {
        Path link = dir.resolve("usb-HUAWEI_Technology_HUAWEI_Mobile-if00-port0");
        try {
            Files.createSymbolicLink(link, dir.resolve("ttyUSB-gone"));
        } catch (IOException | UnsupportedOperationException e) {
            return;
        }

        assertEquals(link.toString(), JSerialCommPortFactory.resolveCurrentDevice(link.toString()),
                "an absent device must still fail as that configured path");
    }
}
