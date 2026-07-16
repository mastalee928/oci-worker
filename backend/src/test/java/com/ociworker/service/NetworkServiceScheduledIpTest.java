package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.oracle.bmc.core.model.PublicIp;
import com.oracle.bmc.core.model.VnicAttachment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkServiceScheduledIpTest {

    @Test
    void selectsNicIndexZeroInsteadOfTheFirstAttachment() {
        VnicAttachment secondary = VnicAttachment.builder().vnicId("secondary").nicIndex(1).build();
        VnicAttachment primary = VnicAttachment.builder().vnicId("primary").nicIndex(0).build();

        assertEquals("primary", NetworkService.selectPrimaryVnicAttachment(List.of(secondary, primary)).getVnicId());
    }

    @Test
    void refusesAmbiguousMultiVnicInstancesWithoutPrimaryIndex() {
        List<VnicAttachment> attachments = List.of(
                VnicAttachment.builder().vnicId("one").build(),
                VnicAttachment.builder().vnicId("two").build());

        assertThrows(OciException.class, () -> NetworkService.selectPrimaryVnicAttachment(attachments));
    }

    @Test
    void detectsReservedPublicIps() {
        PublicIp reserved = PublicIp.builder().lifetime(PublicIp.Lifetime.Reserved).build();
        PublicIp ephemeral = PublicIp.builder().lifetime(PublicIp.Lifetime.Ephemeral).build();

        assertTrue(NetworkService.isReservedPublicIp(reserved));
        assertFalse(NetworkService.isReservedPublicIp(ephemeral));
        assertFalse(NetworkService.isReservedPublicIp(null));
    }
}
