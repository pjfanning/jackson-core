package perf;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/**
 * Manual benchmark comparing three ways of turning the xjb decimal significand
 * ({@code m10}, {@code len}) into digits, to decide whether the digit loop in
 * {@code XJBWriter} is worth replacing.
 *
 * <h2>Why</h2>
 * Splitting {@code XJBWriter.writeDouble} into "mantissa computation" versus "digit
 * emission + formatting" (by returning early right after {@code digitCount}) shows
 * emission is the <em>majority</em> of the cost, not a tail:
 * <pre>
 *   random bits       full 59.4 ns | mantissa-only 21.2 ns | emission 38.2 ns (64%)
 *   decimal-derived   full 43.3 ns | mantissa-only 15.4 ns | emission 27.9 ns (64%)
 *   payload-ish       full 43.1 ns | mantissa-only 18.3 ns | emission 24.7 ns (57%)
 * </pre>
 * So the digit loop is worth attacking. Two candidates were tried:
 * <ul>
 *  <li>{@code lut3} - keep the existing right-to-left structure but emit 3 digits per
 *      iteration from an {@code int[1000]} table, as {@code NumberOutput} does for
 *      integers, instead of 2 from {@code short[100]}.</li>
 *  <li>{@code jeaiii} - James Anhalt's algorithm
 *      (<a href="https://jk-jeon.github.io/posts/2022/02/jeaiii-algorithm/">write-up</a>):
 *      division-free forward (most-significant-digit-first) generation, carrying a Q32
 *      fixed-point fraction and multiplying by 100 per digit pair.</li>
 * </ul>
 *
 * <h2>Result: neither wins</h2>
 * <pre>
 *   random bits      (avg 16.8 digits)  current 28.70 | lut3 27.96 ( -2.6%) | jeaiii 25.45 (-11.3%)
 *   decimal-derived  (avg 10.8 digits)  current 27.90 | lut3 30.64 ( +9.8%) | jeaiii 33.33 (+19.5%)
 *   payload-ish      (avg 13.6 digits)  current 22.03 | lut3 22.80 ( +3.5%) | jeaiii 30.56 (+38.7%)
 * </pre>
 * The reason is {@code m10}'s trailing zeros. Measured over the same workloads:
 * <pre>
 *   random bits      avg 16.8 digits, avg 0.44 trailing zeros
 *   decimal-derived  avg 10.8 digits, avg 6.45 trailing zeros
 *   payload-ish      avg 13.6 digits, avg 9.31 trailing zeros
 * </pre>
 * Right-to-left generation gets trailing-zero elision <em>for free</em>: the
 * "divide by 100 while divisible" pre-loop skips those digits without ever writing
 * them, so on realistic input it does 6-9 digits less work per value. Forward
 * generation cannot do that - the fixed-point fraction is rounded up so that
 * {@code floor()} extraction stays exact, which means it never lands on exactly zero,
 * so there is no "remainder is 0, stop here" test. The digits have to be produced and
 * then trimmed. That is why jeaiii only wins on random bit patterns (16.8 digits,
 * almost no trailing zeros) and loses badly everywhere else.
 *
 * {@code lut3} is roughly a wash and carries two extra costs the numbers here do not
 * show: a 4 KB {@code int[1000]} table against the current 200-byte {@code short[100]}
 * (which stays hot next to other data in a real workload), and a 4-byte store per
 * 3 digits, which needs a slack byte and must be written right-to-left so each stray
 * byte is overwritten by the next store.
 *
 * <h2>Running</h2>
 * {@code java -cp target/classes:target/test-classes perf.DigitEmissionBench}
 *
 * <p>The workload profiles below are synthetic, approximating digit-count and
 * trailing-zero distributions captured from real doubles by instrumenting a copy of
 * {@code XJBWriter} to publish {@code m10}/{@code len} where the emitter sees them.
 * They reproduce the shape of the result, not the exact percentages: the synthetic
 * trailing-zero mix is coarser, so per-workload deltas move by a few points (notably
 * {@code lut3} on "payload-ish"). The quoted figures above are from the real capture.
 */
public class DigitEmissionBench
{
    private final static VarHandle SHORT_LE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private final static VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static void setShort(byte[] b, int p, short v) { SHORT_LE.set(b, p, v); }
    private static void setInt(byte[] b, int p, int v) { INT_LE.set(b, p, v); }

    /** Two ASCII digits (00-99), little-endian packed: 200 bytes. */
    private final static short[] DIGITS = new short[100];

    /**
     * Three ASCII digits (000-999) packed for a {@code setInt} at {@code p-1}, so the
     * stray 4th byte lands to the <i>left</i> of the triple: byte {@code p-1} is stray,
     * {@code p} hundreds, {@code p+1} tens, {@code p+2} units. Writing right-to-left,
     * each stray is overwritten by the next (more significant) store. 4 KB.
     */
    private final static int[] TRIPLETS = new int[1000];

    private final static long DIV1E9 = 4951760157141521100L;  // ceil(2^92 / 10^9), used with >>> 28
    private final static long DIV1E8 = 1441151881L;           // ceil(2^57 / 10^8), used with >>> 57

    static {
        for (int i = 0; i < 100; ++i) {
            DIGITS[i] = (short) ((('0' + i % 10) << 8) | ('0' + i / 10));
        }
        for (int i = 0; i < 1000; ++i) {
            TRIPLETS[i] = (('0' + i % 10) << 24) | (('0' + (i / 10) % 10) << 16)
                    | (('0' + i / 100) << 8);
        }
    }

    /*
    /**********************************************************************
    /* 1. Current: right-to-left, 2 digits/iteration, short[100]
    /**********************************************************************
     */

    // Copy of XJBWriter's private helpers. NOTE: this one writes its leading digit one
    // byte right of the field start (a 2-byte store for a 1-digit head); the real caller
    // undoes that with setShort(pos, buf[pos+1] | 0x2E00) while inserting the '.'.
    static int current(long m10, int len, byte[] buf, int pos) {
        return wsfdLong(m10, pos + len, pos, buf, DIGITS);
    }

    private static int wsfdLong(long x, int p, int pl, byte[] buf, short[] ds) {
        int q0 = (int) x, pos = p, posLim = pl;
        if (q0 != x) {
            long q1 = Math.multiplyHigh(x, 6189700196426901375L) >>> 25; // x / 10^8
            int r1 = (int) (x - q1 * 100000000L);
            int posm8 = pos - 8;
            if (r1 == 0) {
                q0 = (int) q1; pos = posm8;
            } else {
                wfd((int) q1, posm8, posLim, buf, ds); q0 = r1; posLim = posm8;
            }
        }
        return wsfd(q0, pos, posLim, buf, ds);
    }

    private static int wsfd(int x, int p, int posLim, byte[] buf, short[] ds) {
        int q0 = x, q1 = 0, pos = p;
        while (true) {                                  // skip trailing zero pairs entirely
            long qp = q0 * 1374389535L;
            q1 = (int) (qp >> 37);                      // q0 / 100
            if ((qp & 0x1FC0000000L) != 0) break;       // not divisible by 100
            q0 = q1; pos -= 2;
        }
        short d = ds[q0 - q1 * 100];
        setShort(buf, pos - 1, d);
        wfd(q1, pos - 2, posLim, buf, ds);
        return pos + ((0x3039 - d) >>> 31);             // +1 iff the units digit is non-zero
    }

    private static void wfd(int x, int p, int posLim, byte[] buf, short[] ds) {
        int q0 = x, pos = p;
        while (pos > posLim) {
            int q1 = (int) ((q0 * 1374389535L) >> 37);
            setShort(buf, pos - 1, ds[q0 - q1 * 100]);
            q0 = q1; pos -= 2;
        }
    }

    /*
    /**********************************************************************
    /* 2. lut3: right-to-left, 3 digits/iteration, int[1000]
    /**********************************************************************
     */

    static int lut3(long m10, int len, byte[] buf, int pos) {
        int end = pos + len;
        long x = m10;
        if ((int) x == x) {
            return tail3((int) x, end, pos, buf);
        }
        long hi = Math.multiplyHigh(x, DIV1E9) >>> 28;      // x / 10^9
        int lo = (int) (x - hi * 1000000000L);
        int endm9 = end - 9;
        if (lo == 0) {
            return tail3((int) hi, endm9, pos, buf);
        }
        // Low chunk first: every store leaves a stray byte to its left, so the high
        // chunk has to be written afterwards to overwrite the low chunk's stray.
        int last = tail3(lo, end, endm9, buf);
        fd3((int) hi, endm9, pos, buf);
        return last;
    }

    /** Writes digits of {@code x} ending at {@code end}, dropping trailing zeros. */
    private static int tail3(int x, int end, int posLim, byte[] buf) {
        int q0 = x, e = end;
        while (true) {
            int q1 = (int) ((q0 * 274877907L) >>> 38);      // q0 / 1000
            int t = q0 - q1 * 1000;
            if (t != 0) {
                setInt(buf, e - 4, TRIPLETS[t]);
                fd3(q1, e - 3, posLim, buf);
                if (t % 100 == 0) return e - 2;
                if (t % 10 == 0) return e - 1;
                return e;
            }
            q0 = q1; e -= 3;
        }
    }

    private static void fd3(int x, int p, int posLim, byte[] buf) {
        int q0 = x, pos = p;
        while (pos > posLim) {
            int q1 = (int) ((q0 * 274877907L) >>> 38);
            setInt(buf, pos - 4, TRIPLETS[q0 - q1 * 1000]);
            q0 = q1; pos -= 3;
        }
    }

    /*
    /**********************************************************************
    /* 3. jeaiii: forward, division-free, 2 digits/iteration
    /**********************************************************************
     */

    // M[d] = ceil(2^(32+K) / 10^d): (n * M[d]) >>> K is the Q32 fraction of n/10^d for
    // an n of d digits. K is capped at 30 because n * M[d] must stay inside 63 bits.
    private final static int K = 30;
    private final static long[] M = {
        0L, 461168601842738791L, 46116860184273880L, 4611686018427388L, 461168601842739L,
        46116860184274L, 4611686018428L, 461168601843L, 46116860185L
    };

    static int jeaiii(long m10, int len, byte[] buf, int pos) {
        int p;
        if (len <= 9) {
            p = emit((int) m10, len, buf, pos);
        } else {
            long hi = Math.multiplyHigh(m10, DIV1E9) >>> 28; // m10 / 10^9, <= 8 digits
            int lo = (int) (m10 - hi * 1000000000L);         // 9 digits, zero padded
            emit((int) hi, len - 9, buf, pos);
            p = emit(lo, 9, buf, pos + len - 9);
        }
        // The fraction is rounded up so it never reaches exactly zero, so trailing zeros
        // cannot be detected during generation -- they must be written, then trimmed.
        while (p > pos + 1 && buf[p - 1] == '0') {
            --p;
        }
        return p;
    }

    /** Emits exactly {@code d} zero-padded digits of {@code n} at {@code buf[pos..pos+d)}. */
    private static int emit(int n, int d, byte[] buf, int pos) {
        int p = pos, dd = d;
        if (dd == 9) {                                  // peel one digit: chunks must be <= 8
            int q = (int) ((n * DIV1E8) >>> 57);        // n / 10^8
            buf[p++] = (byte) ('0' + q);
            n -= q * 100000000;
            dd = 8;
        }
        // Round up: the fraction must never be underestimated, or a digit whose true
        // value is exact (0.7 * 10) would floor() to one less.
        long y = (n * M[dd] + ((1L << K) - 1)) >>> K;   // Q32 fraction of n / 10^dd
        if ((dd & 1) != 0) {
            y = (y & 0xFFFFFFFFL) * 10L;
            buf[p++] = (byte) ('0' + (int) (y >>> 32));
            --dd;
        }
        while (dd > 0) {
            y = (y & 0xFFFFFFFFL) * 100L;
            setShort(buf, p, DIGITS[(int) (y >>> 32)]);
            p += 2;
            dd -= 2;
        }
        return p;
    }

    /*
    /**********************************************************************
    /* Validation
    /**********************************************************************
     */

    private final static int PAD = 4;                  // left slack: lut3 stores 3 digits with setInt
    private final static byte[] BV = new byte[48];

    // `current` places its leading digit one byte right of the field, so it does not share
    // this contract; it is already covered by the unit tests. Both do the same work, so the
    // timings stay comparable.
    private static void validate(long m10, int len) {
        String want = Long.toString(m10);
        if (want.length() != len) {
            throw new IllegalArgumentException(m10 + " is not " + len + " digits");
        }
        int trimmed = want.length();
        while (trimmed > 1 && want.charAt(trimmed - 1) == '0') {
            --trimmed;
        }
        Arrays.fill(BV, (byte) '#');
        check("lut3", m10, len, want, trimmed, lut3(m10, len, BV, PAD));
        Arrays.fill(BV, (byte) '#');
        check("jeaiii", m10, len, want, trimmed, jeaiii(m10, len, BV, PAD));
    }

    private static void check(String who, long m10, int len, String want, int trimmed, int end) {
        String got = new String(BV, PAD, len, StandardCharsets.ISO_8859_1);
        // bytes past the trim point are don't-care
        if (!got.substring(0, trimmed).equals(want.substring(0, trimmed)) || end != PAD + trimmed) {
            throw new AssertionError(who + " m10=" + m10 + " len=" + len
                    + " -> '" + got + "' end=" + (end - PAD)
                    + ", expected '" + want + "' end=" + trimmed);
        }
    }

    private static void validateAll() {
        Random r = new Random(5);
        for (int len = 1; len <= 17; ++len) {
            long lo = (len == 1) ? 1 : pow10(len - 1);
            long hi = pow10(len);
            for (int i = 0; i < 120000; ++i) {
                long v = lo + (long) (r.nextDouble() * (hi - lo));
                if (v >= hi) v = hi - 1;
                if (v < lo) v = lo;
                validate(v, len);
            }
            for (int i = 0; i < 2000; ++i) {         // dense sweep at both ends of each length
                validate(Math.max(lo, hi - 1 - i), len);
                if (lo + i < hi) validate(lo + i, len);
                long z = (lo + i) / 1000 * 1000;    // trailing-zero heavy
                if (z >= lo && z < hi) validate(z, len);
            }
        }
    }

    /*
    /**********************************************************************
    /* Workloads + benchmark
    /**********************************************************************
     */

    /** digit-count and trailing-zero profiles captured from real double workloads */
    private static long[][] workload(int[] lenPct, int[] tzPct, int n) {
        Random r = new Random(42);
        long[] m = new long[n];
        long[] l = new long[n];
        for (int i = 0; i < n; ++i) {
            int len = pick(r, lenPct);
            int tz = Math.min(pick(r, tzPct), len - 1);
            long v = pow10(len - 1) + (long) (r.nextDouble() * (pow10(len) - pow10(len - 1)));
            if (v >= pow10(len)) v = pow10(len) - 1;
            long p = pow10(tz);
            v = (v / p) * p;                        // force exactly `tz` trailing zeros
            if (v < pow10(len - 1)) v = pow10(len - 1);
            m[i] = v;
            l[i] = len;
        }
        return new long[][] { m, l };
    }

    private static int pick(Random r, int[] pct) {
        int x = r.nextInt(100), acc = 0;
        for (int i = 0; i < pct.length; ++i) {
            acc += pct[i];
            if (x < acc) return i;
        }
        return pct.length - 1;
    }

    interface Emitter { int run(long m10, int len, byte[] buf, int pos); }

    private static double bench(Emitter e, long[] m, long[] l, int reps) {
        byte[] buf = new byte[48];
        long sink = 0;
        long t = System.nanoTime();
        for (int rep = 0; rep < reps; ++rep) {
            for (int i = 0; i < m.length; ++i) {
                sink += e.run(m[i], (int) l[i], buf, PAD);
            }
        }
        long ns = System.nanoTime() - t;
        if (sink == Long.MIN_VALUE) System.out.print("");
        return (double) ns / ((long) reps * m.length);
    }

    private static void report(String label, long[][] w) {
        long[] m = w[0], l = w[1];
        Emitter c = DigitEmissionBench::current;
        Emitter u = DigitEmissionBench::lut3;
        Emitter j = DigitEmissionBench::jeaiii;
        for (int i = 0; i < 40; ++i) { bench(c, m, l, 4); bench(u, m, l, 4); bench(j, m, l, 4); }
        double bc = 1e9, bu = 1e9, bj = 1e9;
        for (int round = 0; round < 9; ++round) {       // best-of, to cut scheduling noise
            bc = Math.min(bc, bench(c, m, l, 80));
            bu = Math.min(bu, bench(u, m, l, 80));
            bj = Math.min(bj, bench(j, m, l, 80));
        }
        double avgLen = 0, avgTz = 0;
        for (int i = 0; i < m.length; ++i) {
            avgLen += l[i];
            long q = m[i];
            while (q % 10 == 0) { q /= 10; ++avgTz; }
        }
        avgLen /= m.length;
        avgTz /= m.length;
        System.out.printf("%-16s (%4.1f digits, %4.1f trailing zeros)  current %5.2f | lut3 %5.2f (%+5.1f%%) | jeaiii %5.2f (%+5.1f%%)  ns/value%n",
                label, avgLen, avgTz, bc, bu, 100 * (bu - bc) / bc, bj, 100 * (bj - bc) / bc);
    }

    private static long pow10(int n) {
        long v = 1;
        while (n-- > 0) v *= 10;
        return v;
    }

    public static void main(String[] args) {
        System.out.println("validating lut3 + jeaiii...");
        validateAll();
        System.out.println("validation OK\n");

        final int N = 1 << 14;
        // index = digit count (0 unused) / trailing-zero count; percentages from capture
        int[] randomLen = new int[18];
        randomLen[16] = 18; randomLen[17] = 82;
        int[] randomTz = { 61, 35, 3, 1 };

        int[] decimalLen = new int[18];
        decimalLen[1]=2; decimalLen[2]=3; decimalLen[3]=4; decimalLen[4]=6; decimalLen[5]=7;
        decimalLen[6]=8; decimalLen[7]=7; decimalLen[8]=7; decimalLen[9]=5; decimalLen[10]=4;
        decimalLen[11]=2; decimalLen[12]=1; decimalLen[16]=16; decimalLen[17]=28;
        int[] decimalTz = { 11, 11, 11, 11, 11, 1, 1, 0, 2, 5, 5, 31 };

        int[] payloadLen = new int[18];
        payloadLen[2]=2; payloadLen[5]=2; payloadLen[6]=22; payloadLen[16]=29; payloadLen[17]=45;
        int[] payloadTz = { 25, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 72 };

        report("random bits", workload(randomLen, randomTz, N));
        report("decimal-derived", workload(decimalLen, decimalTz, N));
        report("payload-ish", workload(payloadLen, payloadTz, N));
    }
}
