package it.uni.reti2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class QuarkusSmokeTest {

    @Test
    public void smoke() {
        // Minimal smoke test to verify Quarkus test infrastructure loads.
        Assertions.assertTrue(true);
    }
}
