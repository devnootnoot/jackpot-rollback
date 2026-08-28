package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EdgeArchGateTest {

    @Test
    void theJvmNamesForOneArchitectureAllCollapseOntoOneId() {
        assertEquals(EdgeArchGate.X86_64, EdgeArchGate.normalize("amd64"));
        assertEquals(EdgeArchGate.X86_64, EdgeArchGate.normalize("x86_64"));
        assertEquals(EdgeArchGate.X86_64, EdgeArchGate.normalize("X86-64"));
        assertEquals(EdgeArchGate.AARCH64, EdgeArchGate.normalize("aarch64"));
        assertEquals(EdgeArchGate.AARCH64, EdgeArchGate.normalize("arm64"));
        assertEquals(EdgeArchGate.AARCH64, EdgeArchGate.normalize(" ARM64 "));
    }

    @Test
    void anUnsetExpectationWarnsAndKeepsBrokering() {
        EdgeArchGate.Verdict v = EdgeArchGate.evaluate("", "amd64");
        assertTrue(v.brokerAllowed(),
                "unset is the shipped default - refusing it would stop every existing deployment"
                        + " and the local dev stack from brokering at all");
        assertTrue(v.warning(), "an unpinned edge must say so loudly");
        assertTrue(v.message().contains(EdgeArchGate.ENV));
        assertTrue(v.message().contains(EdgeArchGate.X86_64),
                "the warning must name the value the operator should set on this box");
        assertTrue(EdgeArchGate.evaluate(null, "aarch64").brokerAllowed());
        assertTrue(EdgeArchGate.evaluate("   ", "aarch64").warning());
    }

    @Test
    void aTypoInTheExpectationRefusesBrokeringRatherThanPassing() {
        EdgeArchGate.Verdict v = EdgeArchGate.evaluate("x64_86", "amd64");
        assertFalse(v.brokerAllowed());
        assertTrue(v.message().contains("x64_86"));
    }

    @Test
    void theWrongArchitectureRefusesBrokering() {
        EdgeArchGate.Verdict v = EdgeArchGate.evaluate("x86_64", "aarch64");
        assertFalse(v.brokerAllowed());
        assertEquals(EdgeArchGate.X86_64, v.expected());
        assertEquals(EdgeArchGate.AARCH64, v.running());
        assertFalse(EdgeArchGate.evaluate("aarch64", "amd64").brokerAllowed());
    }

    @Test
    void aMatchingArchitectureAllowsBrokering() {
        assertTrue(EdgeArchGate.evaluate("x86_64", "amd64").brokerAllowed());
        assertTrue(EdgeArchGate.evaluate("amd64", "x86_64").brokerAllowed());
        assertTrue(EdgeArchGate.evaluate("aarch64", "arm64").brokerAllowed());
        assertFalse(EdgeArchGate.evaluate("amd64", "x86_64").warning(),
                "a pinned box that agrees with its pin has nothing to warn about");
    }

    @Test
    void theBoxThisTestRunsOnCanPinItself() {
        String running = EdgeArchGate.normalize(System.getProperty("os.arch"));
        assertTrue(EdgeArchGate.IDS.contains(running),
                "os.arch=" + System.getProperty("os.arch") + " is not an architecture the gate"
                        + " knows, so no value of " + EdgeArchGate.ENV + " would let this box"
                        + " broker");
        assertTrue(EdgeArchGate.evaluate(running, System.getProperty("os.arch")).brokerAllowed());
    }
}
