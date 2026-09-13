package mods.dhousefe.kamaloka.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Microbenchmark JMH para avaliação de vazão e contenção das rotinas críticas do KamalokaInstance.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class KamalokaBenchmark {

    private long sampleTimestamp;
    private long[] sampleTimestamps;
    private ConcurrentHashMap<Integer, List<Long>> playerEntryTimesMap;
    private static final int MAX_DAILY_ENTRIES = 2;
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
    private static final long TIME_ZONE_OFFSET_MS = TimeZone.getDefault().getRawOffset();
    private static final long MILLIS_PER_DAY = 86_400_000L;

    @Setup(Level.Trial)
    public void setup() {
        sampleTimestamp = System.currentTimeMillis() - 10_000L;
        sampleTimestamps = new long[]{
            sampleTimestamp - 3600_000L,
            sampleTimestamp - 1800_000L,
            sampleTimestamp
        };

        playerEntryTimesMap = new ConcurrentHashMap<>();
        for (int i = 1; i <= 5000; i++) {
            List<Long> entries = new ArrayList<>();
            entries.add(System.currentTimeMillis() - 7200_000L);
            playerEntryTimesMap.put(i, entries);
        }
    }

    // --- 1. Date Check: Legacy vs Optimized ---

    private boolean isTodayLegacy(long timestamp) {
        if (timestamp == 0) return false;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        return sdf.format(new Date(timestamp)).equals(sdf.format(new Date()));
    }

    private boolean isTodayEpochMath(long timestamp) {
        if (timestamp == 0) return false;
        long now = System.currentTimeMillis();
        long dayTimestamp = (timestamp + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY;
        long dayNow = (now + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY;
        return dayTimestamp == dayNow;
    }

    private boolean isTodayJavaTime(long timestamp) {
        if (timestamp == 0) return false;
        LocalDate date = Instant.ofEpochMilli(timestamp).atZone(DEFAULT_ZONE).toLocalDate();
        return date.equals(LocalDate.now(DEFAULT_ZONE));
    }

    @Benchmark
    public void benchIsToday_Legacy(Blackhole bh) {
        bh.consume(isTodayLegacy(sampleTimestamp));
    }

    @Benchmark
    public void benchIsToday_JavaTime(Blackhole bh) {
        bh.consume(isTodayJavaTime(sampleTimestamp));
    }

    @Benchmark
    public void benchIsToday_EpochMath(Blackhole bh) {
        bh.consume(isTodayEpochMath(sampleTimestamp));
    }

    // --- 2. Dungeon ID Calculation: Solo & Party ---

    private int getSoloDungeonIdForLevel(int level) {
        if (level < 20 || level > 80) return -1;
        int dungeonId = 10 + ((level - 20) / 5);
        return Math.min(dungeonId, 21);
    }

    private int getPartyDungeonIdForLevel(int level) {
        if (level < 20 || level > 80) return -1;
        int dungeonId = 22 + ((level - 20) / 5);
        return Math.min(dungeonId, 33);
    }

    @Benchmark
    public void benchDungeonIdLookup(Blackhole bh) {
        int lvl = 20 + (ThreadLocalRandom.current().nextInt(65));
        int soloId = getSoloDungeonIdForLevel(lvl);
        int partyId = getPartyDungeonIdForLevel(lvl);
        bh.consume(soloId);
        bh.consume(partyId);
    }

    // --- 3. Daily Entries Validation (Stream vs Imperative Loop) ---

    @Benchmark
    @Threads(8)
    public void benchDailyEntriesCheck_Stream(Blackhole bh) {
        int playerId = ThreadLocalRandom.current().nextInt(1, 5000);
        List<Long> entryTimes = playerEntryTimesMap.getOrDefault(playerId, Collections.emptyList());
        List<Long> todayEntries = entryTimes.stream()
            .filter(this::isTodayLegacy)
            .collect(Collectors.toList());
        bh.consume(todayEntries.size() <= MAX_DAILY_ENTRIES);
    }

    @Benchmark
    @Threads(8)
    public void benchDailyEntriesCheck_Optimized(Blackhole bh) {
        int playerId = ThreadLocalRandom.current().nextInt(1, 5000);
        List<Long> entryTimes = playerEntryTimesMap.getOrDefault(playerId, Collections.emptyList());
        int count = 0;
        long now = System.currentTimeMillis();
        long dayNow = (now + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY;
        for (int i = 0; i < entryTimes.size(); i++) {
            long ts = entryTimes.get(i);
            if (ts != 0 && ((ts + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY) == dayNow) {
                count++;
            }
        }
        bh.consume(count <= MAX_DAILY_ENTRIES);
    }
}
