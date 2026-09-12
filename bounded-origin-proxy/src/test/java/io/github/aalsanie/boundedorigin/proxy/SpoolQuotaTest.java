package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SpoolQuotaTest {
  @Test
  void tracksBytesFilesAndReleasesIdempotently() {
    SpoolQuota quota = new SpoolQuota(10, 2);
    SpoolQuota.Reservation first = quota.openFile();
    first.reserve(4);
    SpoolQuota.Reservation second = quota.openFile();
    second.reserve(6);

    assertEquals(10, quota.bytes());
    assertEquals(2, quota.files());
    assertThrows(SpoolQuota.SpoolLimitExceededException.class, quota::openFile);
    assertThrows(SpoolQuota.SpoolLimitExceededException.class, () -> first.reserve(1));

    first.close();
    first.close();
    assertEquals(6, quota.bytes());
    assertEquals(1, quota.files());
    second.close();
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void validationAndClosedQuotaFailClosed() {
    assertThrows(IllegalArgumentException.class, () -> new SpoolQuota(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpoolQuota(1, 0));

    SpoolQuota quota = new SpoolQuota(2, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    assertThrows(IllegalArgumentException.class, () -> reservation.reserve(-1));
    reservation.reserve(1);
    quota.close();
    assertThrows(IllegalStateException.class, quota::openFile);
    assertThrows(IllegalStateException.class, () -> reservation.reserve(1));
    reservation.close();
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }
}
