package me.nootnoot.relay;

import java.net.InetAddress;
import java.net.UnknownHostException;
import me.nootnoot.sim.net.SlotTokens;

public final class RelayBindGate {

    public static final String SECRET_ENV = "RELAY_SLOT_SECRET";
    public static final String BIND_ENV = "RELAY_BIND";
    public static final String PUBLIC_ENV = "RELAY_ALLOW_PUBLIC_WITHOUT_SECRET";
    public static final String OPEN_ENV = "RELAY_ALLOW_UNAUTHENTICATED";

    public static final String LOOPBACK = "127.0.0.1";
    public static final String WILDCARD = "0.0.0.0";

    public static final int EXIT_CONFIG = 78;

    public enum Mode {
        DERIVED,
        PINNED_LOOPBACK,
        PINNED_PUBLIC,
        OPEN,
        REFUSED
    }

    public record Verdict(boolean start, Mode mode, String bindHost, boolean slotAuthentication,
                          boolean severe, String message) {
    }

    private RelayBindGate() {
    }

    public static Verdict evaluate(String secret, String bindRaw, boolean allowPublicWithoutSecret,
                                   boolean allowUnauthenticated) {
        boolean hasSecret = secret != null && !secret.isBlank();
        String bind = bindRaw == null ? "" : bindRaw.trim();
        boolean explicitBind = !bind.isEmpty();

        if (hasSecret) {
            String host = explicitBind ? bind : WILDCARD;
            return new Verdict(true, Mode.DERIVED, host, true, false,
                    derived(host, allowUnauthenticated));
        }
        if (allowUnauthenticated) {
            String host = explicitBind ? bind : WILDCARD;
            return new Verdict(true, Mode.OPEN, host, false, true, open(host));
        }
        if (!explicitBind) {
            return new Verdict(true, Mode.PINNED_LOOPBACK, LOOPBACK, true, false,
                    loopback(LOOPBACK, false));
        }
        if (isLoopback(bind)) {
            return new Verdict(true, Mode.PINNED_LOOPBACK, bind, true, false, loopback(bind, true));
        }
        if (allowPublicWithoutSecret) {
            return new Verdict(true, Mode.PINNED_PUBLIC, bind, true, true, publicPinned(bind));
        }
        return new Verdict(false, Mode.REFUSED, bind, true, true, refused(bind));
    }

    public static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (h.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }

    private static String derived(String host, boolean allowUnauthenticated) {
        String base = "slot binding is verified against " + SECRET_ENV + ": a HELLO is accepted only"
                + " if its token equals HMAC-SHA256(secret, \"" + SlotTokens.LABEL + "\" ||"
                + " sessionId || slot). The relay mints nothing and trusts no first arrival, so the"
                + " same secret must be configured on core as rollback.relay-slot-secret /"
                + " ROLLBACK_RELAY_SLOT_SECRET. Because the secret is set, this relay binds " + host
                + ", which is the only bind that serves players over the network.";
        return allowUnauthenticated
                ? base + " " + OPEN_ENV + "=true is IGNORED while a slot secret is set -"
                        + " verification is the stronger rule and stays on."
                : base;
    }

    private static String loopback(String host, boolean explicit) {
        return "#### RELAY BOUND TO LOOPBACK: NO " + SECRET_ENV + " ####"
                + " This relay has no slot secret, so slot binding is trust on first use: the first"
                + " HELLO for a session pins a >=" + RelayServer.MIN_SLOT_TOKEN_BYTES + " byte token"
                + " and every later HELLO must present it, which means whoever reaches the relay"
                + " first owns the slot. That is only safe when nothing off this machine can reach"
                + " the socket, so the relay is listening on " + host
                + (explicit ? " as " + BIND_ENV + " asks" : " instead of " + WILDCARD)
                + " and is unreachable from anywhere else - including from other containers, and"
                + " including through a published docker port. This is the local development shape."
                + " To serve real players, set " + SECRET_ENV + " to the same value core has as"
                + " ROLLBACK_RELAY_SLOT_SECRET; the relay then binds " + WILDCARD + " on its own and"
                + " verifies every bind.";
    }

    private static String publicPinned(String host) {
        return "#### WEAK SLOT BINDING ON A ROUTABLE ADDRESS ####"
                + " " + PUBLIC_ENV + "=true, so this relay is binding " + host + " with no "
                + SECRET_ENV + ". Slot binding is trust on first use: any host that can reach this"
                + " socket and guess or observe a sessionId can pin a slot before the player"
                + " whose slot it is, and then speak for that player for the whole match. Nothing"
                + " downstream can tell the difference, because the sim believes whatever arrives on"
                + " a bound slot. Set " + SECRET_ENV + " and remove " + PUBLIC_ENV + ".";
    }

    private static String open(String host) {
        return "#### SLOT AUTHENTICATION IS OFF ####"
                + " " + OPEN_ENV + "=true, so this relay is binding " + host + " and accepts every"
                + " HELLO. Any reachable host may bind either slot of any session and inject input"
                + " for a player - it does not even have to arrive first. Isolated local testing"
                + " only. Set " + SECRET_ENV + " and remove " + OPEN_ENV + ".";
    }

    private static String refused(String host) {
        return "#### RELAY REFUSED TO START: NO " + SECRET_ENV + " ON A ROUTABLE BIND ####"
                + " " + BIND_ENV + "=" + host + " asks this relay to listen where other machines can"
                + " reach it, but no slot secret is configured, so slot binding would fall back to"
                + " trust on first use and the first HELLO to arrive would own a player's slot."
                + " Refusing to start rather than forwarding a match nobody can vouch for."
                + " Fix it one of three ways: set " + SECRET_ENV + " to the same value core carries"
                + " as ROLLBACK_RELAY_SLOT_SECRET, which is the production answer and also makes"
                + " this relay bind " + WILDCARD + " by itself; or drop " + BIND_ENV + " so the"
                + " relay listens on " + LOOPBACK + " only, which is the development answer; or set "
                + PUBLIC_ENV + "=true to keep this exact configuration as a deliberate, logged"
                + " decision. There is no fourth way, and no default that ends up here quietly.";
    }
}
