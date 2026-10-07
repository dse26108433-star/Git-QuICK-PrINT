package edu.campus.print.orders;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploaded files are read into memory to be checked. How many at once is
 * decided by their size, so a rush of big files waits its turn instead of
 * filling the server's memory.
 */
class CheckShareTest {

    private static final long MB = 1024 * 1024;

    @Test
    void aFileTakesAsMuchOfTheCheckingMemoryAsItIsBig() {
        // 64 MB to share, four small files at once
        assertThat(OrderService.checkShare(100 * 1024, 64, 16)).isEqualTo(16);       // a small file: a fair share
        assertThat(OrderService.checkShare(15 * MB, 64, 16)).isEqualTo(16);
        assertThat(OrderService.checkShare(30 * MB, 64, 16)).isEqualTo(31);          // a big one: what it is
        assertThat(OrderService.checkShare(50 * MB, 64, 16)).isEqualTo(51);
        assertThat(OrderService.checkShare(500 * MB, 64, 16)).isEqualTo(64);         // never more than there is
    }

    @Test
    void bigFilesAreCheckedOneAfterTheOtherSmallOnesTogether() {
        Semaphore memory = new Semaphore(64, true);
        // four small files fit together, a fifth waits
        for (int i = 0; i < 4; i++) assertThat(memory.tryAcquire(OrderService.checkShare(2 * MB, 64, 16))).isTrue();
        assertThat(memory.tryAcquire(OrderService.checkShare(2 * MB, 64, 16))).isFalse();
        memory.release(64);
        // one 50 MB file: no second big one beside it, and no small one either
        assertThat(memory.tryAcquire(OrderService.checkShare(50 * MB, 64, 16))).isTrue();
        assertThat(memory.tryAcquire(OrderService.checkShare(50 * MB, 64, 16))).isFalse();
        assertThat(memory.tryAcquire(OrderService.checkShare(1 * MB, 64, 16))).isFalse();
        memory.release(51);
        // two 30 MB files fit (62 of 64), a third does not
        assertThat(memory.tryAcquire(OrderService.checkShare(30 * MB, 64, 16))).isTrue();
        assertThat(memory.tryAcquire(OrderService.checkShare(30 * MB, 64, 16))).isTrue();
        assertThat(memory.tryAcquire(OrderService.checkShare(30 * MB, 64, 16))).isFalse();
    }
}
