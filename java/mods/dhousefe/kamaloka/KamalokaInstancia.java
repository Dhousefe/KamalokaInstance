package mods.dhousefe.kamaloka;

import ext.mods.InstanceMap.InstanceManager;
import ext.mods.InstanceMap.MapInstance;
import ext.mods.commons.logging.CLogger;
import ext.mods.commons.pool.ConnectionPool;
import ext.mods.dungeon.Dungeon;
import ext.mods.dungeon.DungeonManager;
import ext.mods.dungeon.DungeonTemplate;
import ext.mods.dungeon.data.DungeonData;
import ext.mods.extensions.interfaces.L2JExtension;
import ext.mods.extensions.listener.OnDeathListener;
import ext.mods.extensions.listener.command.OnBypassCommandListener;
import ext.mods.extensions.listener.manager.BypassCommandManager;
import ext.mods.extensions.listener.manager.CreatureListenerManager;
import ext.mods.gameserver.data.SkillTable;
import ext.mods.gameserver.data.manager.SpawnManager;
import ext.mods.gameserver.data.xml.SysString;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.enums.ZoneId;
import ext.mods.gameserver.model.actor.Attackable;
import ext.mods.gameserver.model.actor.Creature;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.gameserver.model.actor.player.DungeonState;
import ext.mods.gameserver.model.group.Party;
import ext.mods.gameserver.model.location.Location;
import ext.mods.gameserver.model.spawn.ASpawn;
import ext.mods.gameserver.model.spawn.Spawn;
import ext.mods.gameserver.network.serverpackets.CreatureSay;
import ext.mods.gameserver.network.serverpackets.ExShowScreenMessage;
import ext.mods.gameserver.network.serverpackets.ExShowScreenMessage.SMPOS;
import ext.mods.gameserver.network.serverpackets.NpcHtmlMessage;
import ext.mods.gameserver.skills.L2Skill;
import br.project.spi.Extension;
import br.project.spi.ExtensionContext;

import java.lang.reflect.Field;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mod Kamaloka Instancia.
 *
 * Compatível com o ambiente compilado em server.jar e BrProject-2026.
 * Implementa L2JExtension (legado) e br.project.spi.Extension (Phase 2/3 SPI).
 * Integrado com controle transacional de atomicidade, persistência SQL de entradas
 * e conformidade estrita com kamaloka.properties.
 *
 * @author Dhousefe
 * @version 5.0
 */
public final class KamalokaInstancia implements L2JExtension, Extension, OnBypassCommandListener, OnDeathListener {
    private static final CLogger LOGGER = new CLogger(KamalokaInstancia.class.getName());

    // --- Configurações Gerais ---
    private static final String INSTANCE_NAME = "KamalokaInstancia";
    private static final String BYPASS_PREFIX = "kamaloka_";
    private static final int INSTANCE_COOLDOWN_MINUTES = 320;
    private static final int PARTY_RANGE_TO_REWARD = 1500;
    private static final int MAX_PARTY_DISTANCE = 1500;

    // --- SPI Metadata (BrProject-2026) ---
    public static final String SPI_ID = "kamaloka-instance";
    public static final String SPI_VERSION = "5.0.0";

    // --- Otimização de Cálculo de Data (Zero Allocation) ---
    private static final long TIME_ZONE_OFFSET_MS = TimeZone.getDefault().getRawOffset();
    private static final long MILLIS_PER_DAY = 86_400_000L;

    // --- Controle de Concorrência e Atomicidade ---
    private final Set<Integer> _lockedPlayers = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean allowRepeat = new AtomicBoolean(false);
    private static final AtomicBoolean _deathListenerRegistered = new AtomicBoolean(false);

    // --- Cache Local de Entradas Diárias (PlayerId -> (DayEpoch, Count)) ---
    private static final class DailyEntryCache {
        final long dayEpoch;
        int count;

        DailyEntryCache(long dayEpoch, int count) {
            this.dayEpoch = dayEpoch;
            this.count = count;
        }
    }
    private final ConcurrentHashMap<Integer, DailyEntryCache> _dailyCache = new ConcurrentHashMap<>();

    // --- Gerenciador de Instâncias Ativas ---
    private final ConcurrentHashMap<Integer, KamalokaDungeon> _dungeons = new ConcurrentHashMap<>();

    // --- Tarefas Agendadas e Executor ---
    private final AtomicReference<ScheduledFuture<?>> nextInstanceAnnounceTask = new AtomicReference<>();
    private ScheduledExecutorService _executor;

    private static class SingletonHolder {
        private static final KamalokaInstancia INSTANCE = new KamalokaInstancia();
    }

    public static KamalokaInstancia getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private String getLocalizedString(Player player, String key) {
        Locale locale = player.getLocale();
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        return SysString.getInstance().get(locale, key);
    }

    @Override
    public void onLoad() {
        try {
            copyResourceIfNotExists("mods/kamaloka/configs/kamaloka.properties", "./config/kamaloka.properties");
            copyResourceIfNotExists("mods/kamaloka/xmls/Kamaloka_Dungeon.xml", "./data/custom/mods/Kamaloka_Dungeon.xml");
            copyResourceIfNotExists("mods/kamaloka/xmls/Kamaloka.xml", "./data/xml/npcs/kamaloka.xml");
            copyResourceIfNotExists("mods/kamaloka/htmls/322.htm", "./data/locale/en_US/html/kamaloka/322.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-busy.htm", "./data/locale/en_US/html/kamaloka/322-busy.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-completed.htm", "./data/locale/en_US/html/kamaloka/322-completed.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-daily-limit.htm", "./data/locale/en_US/html/kamaloka/322-daily-limit.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-inprogress.htm", "./data/locale/en_US/html/kamaloka/322-inprogress.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-no-party.htm", "./data/locale/en_US/html/kamaloka/322-no-party.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/322-teleported.htm", "./data/locale/en_US/html/kamaloka/322-teleported.htm");
            copyResourceIfNotExists("mods/kamaloka/htmls/admin_panel.htm", "./data/locale/en_US/html/kamaloka/admin_panel.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao extrair arquivos padrao de configuracao.", e);
        }

        Config.load();
        initDatabase();

        try {
            DungeonData.getInstance().parseDataFile("custom/mods/Kamaloka_Dungeon.xml");
        } catch (Exception e) {
            LOGGER.warn("[" + getName() + "] Falha ao carregar Kamaloka_Dungeon.xml.", e);
        }

        _executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        BypassCommandManager.getInstance().registerBypassListener(this);
        if (_deathListenerRegistered.compareAndSet(false, true)) {
            CreatureListenerManager.getInstance().addDeathListener(this);
        }

        LOGGER.info("[" + getName() + "] KamalokaInstance v" + SPI_VERSION + " carregado com sucesso.");
    }

    @Override
    public void onDisable() {
        BypassCommandManager.getInstance().unregisterBypassListener(this);
        _dungeons.values().forEach(kd -> kd.cancelDungeon("O servidor esta sendo desligado."));
        _dungeons.clear();
        _lockedPlayers.clear();
        _dailyCache.clear();
        shutdownExecutor();
        LOGGER.info("[" + getName() + "] Descarregado.");
    }

    @Override
    public String getName() {
        return INSTANCE_NAME;
    }

    @Override
    public String id() {
        return SPI_ID;
    }

    @Override
    public String version() {
        return SPI_VERSION;
    }

    @Override
    public void onLoad(ExtensionContext context) {
        onLoad();
        if (context != null) {
            context.info("[" + id() + " v" + version() + "] Inicializado via Extension SPI.");
        }
    }

    @Override
    public void onEnable(ExtensionContext context) {
        if (context != null) {
            context.info("[" + id() + "] Ativado no contexto do BrProject-2026.");
        }
    }

    @Override
    public void onDisable(ExtensionContext context) {
        onDisable();
        if (context != null) {
            context.info("[" + id() + "] Desativado via Extension SPI.");
        }
    }

    @Override
    public boolean onBypass(Player player, String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        if (command.equals("bp_showMarketPlace") || command.equals("kamaloka") || command.equals("voiced_kamaloka")) {
            showHtml(player, "322.htm");
            return true;
        }

        if (!command.startsWith(BYPASS_PREFIX)) {
            return false;
        }

        final String action = command.substring(BYPASS_PREFIX.length());
        _executor.execute(() -> {
            switch (action) {
                case "enter" -> handleEnterInstance(player, false);
                case "enter_solo" -> handleEnterInstance(player, true);
                case "leave" -> handleLeaveInstance(player);
                case "toggle_repeat" -> handleToggleRepeat(player);
                default -> showHtml(player, "322.htm");
            }
        });
        return true;
    }

    private void initDatabase() {
        
        final String sql = "CREATE TABLE IF NOT EXISTS character_kamaloka_entries (" +
                "char_id INT NOT NULL, " +
                "entry_date VARCHAR(20) NOT NULL, " +
                "entry_count INT NOT NULL DEFAULT 1, " +
                "last_entry_time BIGINT NOT NULL, " +
                "PRIMARY KEY (char_id, entry_date)" +
                ");";
        try (Connection con = ConnectionPool.getConnection();
             Statement st = con.createStatement()) {
            st.executeUpdate(sql);
            LOGGER.info("[" + getName() + "] Tabela character_kamaloka_entries verificada/criada com sucesso no banco de dados.");
        } catch (Throwable t) {
            LOGGER.warn("[" + getName() + "] Aviso na inicializacao da tabela no banco (usando fallback em memoria se necessario): " + t.getMessage());
        }
    }

    private int getDailyEntries(int playerId) {
        long now = System.currentTimeMillis();
        long dayEpoch = (now + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY;
        String today = String.valueOf(dayEpoch);

        DailyEntryCache cached = _dailyCache.get(playerId);
        if (cached != null && cached.dayEpoch == dayEpoch) {
            return cached.count;
        }

        int countFromDb = 0;
        try (Connection con = ConnectionPool.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT entry_count FROM character_kamaloka_entries WHERE char_id = ? AND entry_date = ?")) {
            ps.setInt(1, playerId);
            ps.setString(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    countFromDb = rs.getInt("entry_count");
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[" + getName() + "] Erro ao buscar entradas diarias no banco para charId " + playerId + ": " + t.getMessage());
        }

        _dailyCache.put(playerId, new DailyEntryCache(dayEpoch, countFromDb));
        return countFromDb;
    }

    private void incrementDailyEntry(int playerId) {
        long now = System.currentTimeMillis();
        long dayEpoch = (now + TIME_ZONE_OFFSET_MS) / MILLIS_PER_DAY;
        String today = String.valueOf(dayEpoch);

        _dailyCache.compute(playerId, (id, old) -> {
            if (old == null || old.dayEpoch != dayEpoch) {
                return new DailyEntryCache(dayEpoch, 1);
            }
            old.count++;
            return old;
        });

        try (Connection con = ConnectionPool.getConnection()) {

            try (PreparedStatement psUpdate = con.prepareStatement(
                    "UPDATE character_kamaloka_entries SET entry_count = entry_count + 1, last_entry_time = ? WHERE char_id = ? AND entry_date = ?")) {
                psUpdate.setLong(1, now);
                psUpdate.setInt(2, playerId);
                psUpdate.setString(3, today);
                int rows = psUpdate.executeUpdate();
                if (rows == 0) {
                    try (PreparedStatement psInsert = con.prepareStatement(
                            "INSERT INTO character_kamaloka_entries (char_id, entry_date, entry_count, last_entry_time) VALUES (?, ?, 1, ?)")) {
                        psInsert.setInt(1, playerId);
                        psInsert.setString(2, today);
                        psInsert.setLong(3, now);
                        psInsert.executeUpdate();
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[" + getName() + "] Erro ao registrar entrada no banco para charId " + playerId + ": " + t.getMessage());
        }
    }


    private boolean tryLockParticipants(List<Player> players) {
        List<Integer> acquired = new ArrayList<>(players.size());
        List<Player> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparingInt(Player::getObjectId));

        for (Player p : sorted) {
            if (!_lockedPlayers.add(p.getObjectId())) {
                for (Integer id : acquired) {
                    _lockedPlayers.remove(id);
                }
                return false;
            }
            acquired.add(p.getObjectId());
        }
        return true;
    }

    private void unlockParticipants(List<Player> players) {
        if (players == null) return;
        for (Player p : players) {
            if (p != null) {
                _lockedPlayers.remove(p.getObjectId());
            }
        }
    }


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

    private boolean isPlayerInDungeon(Player player) {
        if (player == null) return false;
        try {
            if (DungeonState.get(player) != null) return true;
        } catch (Throwable ignored) {}
        try {
            if (DungeonManager.getInstance().isInDungeon(player)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private Dungeon getPlayerDungeon(Player player) {
        if (player == null) return null;
        try {
            Object session = DungeonState.get(player);
            if (session instanceof Dungeon d) return d;
        } catch (Throwable ignored) {}
        for (KamalokaDungeon d : _dungeons.values()) {
            List<Player> members = d.getPlayers();
            if (members != null && members.contains(player)) {
                return d;
            }
        }
        return null;
    }


    private boolean checkPlayerRestrictions(Player player) {
        if (isPlayerInDungeon(player)) {
            player.sendMessage(getLocalizedString(player, "12000"));
            return false;
        }
        if (player.isInCombat()) {
            player.sendMessage(getLocalizedString(player, "12001"));
            return false;
        }
        if (player.isInOlympiadMode()) {
            player.sendMessage(getLocalizedString(player, "12002"));
            return false;
        }
        if (player.getPvpFlag() != 0) {
            player.sendMessage(getLocalizedString(player, "12003"));
            return false;
        }
        if (player.isDead()) {
            player.sendMessage(getLocalizedString(player, "12004"));
            return false;
        }
        if (player.isFakeDeath()) {
            player.sendMessage(getLocalizedString(player, "12005"));
            return false;
        }
        if (player.isFishing()) {
            player.sendMessage(getLocalizedString(player, "12006"));
            return false;
        }
        if (player.isInDuel()) {
            player.sendMessage(getLocalizedString(player, "12007"));
            return false;
        }
        if (player.isInArena()) {
            player.sendMessage(getLocalizedString(player, "12008"));
            return false;
        }
        if (player.isInJail()) {
            player.sendMessage(getLocalizedString(player, "12009"));
            return false;
        }
        if (player.isInStoreMode()) {
            player.sendMessage(getLocalizedString(player, "12010"));
            return false;
        }
        if (player.isInObserverMode()) {
            player.sendMessage(getLocalizedString(player, "12011"));
            return false;
        }
        if (player.getCast().isCastingNow()) {
            player.sendMessage(getLocalizedString(player, "12012"));
            return false;
        }
        try {
            if ((Boolean) player.getClass().getMethod("isInTournament").invoke(player)) {
                player.sendMessage(getLocalizedString(player, "12035"));
                return false;
            }
        } catch (Exception ignored) {}

        try {
            if ((Boolean) player.getClass().getMethod("isInCTF").invoke(player)) {
                player.sendMessage(getLocalizedString(player, "12036"));
                return false;
            }
        } catch (Exception ignored) {}

        try {
            if ((Boolean) player.getClass().getMethod("isInTvT").invoke(player)) {
                player.sendMessage(getLocalizedString(player, "12037"));
                return false;
            }
        } catch (Exception ignored) {}

        try {
            if ((Boolean) player.getClass().getMethod("isInDM").invoke(player)) {
                player.sendMessage(getLocalizedString(player, "12038"));
                return false;
            }
        } catch (Exception ignored) {}

        try {
            if ((Boolean) player.getClass().getMethod("isInLM").invoke(player)) {
                player.sendMessage(getLocalizedString(player, "12039"));
                return false;
            }
        } catch (Exception ignored) {}

        if (!allowRepeat.get()) {
            int entries = getDailyEntries(player.getObjectId());
            if (entries >= Config.MAX_DAILY_ENTRIES) {
                showHtml(player, "322-daily-limit.htm");
                return false;
            }
        }
        return true;
    }

    private boolean checkEntryFee(Player player, boolean isSolo) {
        if (isSolo) {
            if (Config.SOLO_ENTRY_FEE_ENABLED) {
                if (player.getInventory().getItemCount(Config.SOLO_ENTRY_FEE_ITEM_ID) < Config.SOLO_ENTRY_FEE_ITEM_COUNT) {
                    player.sendMessage(getLocalizedString(player, "12044"));
                    return false;
                }
            }
        } else {
            if (Config.PARTY_ENTRY_FEE_ENABLED) {
                if (player.getInventory().getItemCount(Config.PARTY_ENTRY_FEE_ITEM_ID) < Config.PARTY_ENTRY_FEE_ITEM_COUNT) {
                    return false;
                }
            }
        }
        return true;
    }


    private void handleEnterInstance(Player player, boolean isSolo) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (isSolo) {
            if (player.isInParty()) {
                player.sendMessage(getLocalizedString(player, "12041"));
                return;
            }
        } else {
            final Party party = player.getParty();
            if (party == null) {
                showHtml(player, "322-no-party.htm");
                return;
            }
            if (!party.isLeader(player)) {
                player.sendMessage(getLocalizedString(player, "12042"));
                return;
            }
        }

        final List<Player> participants = isSolo ? Collections.singletonList(player) : new ArrayList<>(player.getParty().getMembers());

        if (!tryLockParticipants(participants)) {
            showHtml(player, "322-busy.htm");
            return;
        }

        boolean validationOk = false;
        try {
            for (Player member : participants) {
                if (member == null || !member.isOnline()) {
                    return;
                }
                if (!isSolo && !member.equals(player) && !member.isIn3DRadius(player, MAX_PARTY_DISTANCE)) {
                    player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka",
                            getLocalizedString(player, "12043").replace("{name}", member.getName())));
                    return;
                }
                if (!checkPlayerRestrictions(member)) {
                    if (!isSolo) {
                        player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka",
                                getLocalizedString(player, "12016").replace("{name}", member.getName())));
                    }
                    return;
                }
                if (!checkEntryFee(member, isSolo)) {
                    if (!isSolo) {
                        player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka",
                                getLocalizedString(player, "12045").replace("{name}", member.getName())));
                    }
                    return;
                }
            }
            validationOk = true;
        } finally {
            if (!validationOk) {
                unlockParticipants(participants);
            }
        }

        if (player.isInsideZone(ZoneId.PEACE)) {
            player.sendMessage(getLocalizedString(player, "12040"));
            proceedToInstance(participants, isSolo, null);
            return;
        }

        try {
            final L2Skill channelingSkill = SkillTable.getInstance().getInfo(Config.CHANNELING_SKILL_ID, 1);
            if (channelingSkill == null) {
                LOGGER.warn("[" + getName() + "] Skill de canalizacao nao encontrada: " + Config.CHANNELING_SKILL_ID);
                player.sendMessage(getLocalizedString(player, "12022"));
                proceedToInstance(participants, isSolo, null);
                return;
            }

            final int castTime = channelingSkill.getHitTime() + 100;
            if (isSolo) {
                player.abortAll(true);
                player.getCast().doCast(channelingSkill, player, null);
                player.sendMessage(getLocalizedString(player, "12014"));
            } else {
                player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka", getLocalizedString(player, "12015")));
                participants.forEach(member -> {
                    member.abortAll(true);
                    member.getCast().doCast(channelingSkill, member, null);
                });
            }

            _executor.schedule(() -> proceedToInstance(participants, isSolo, channelingSkill), castTime, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.error("[" + getName() + "] Erro durante a canalizacao para a instancia: ", e);
            unlockParticipants(participants);
        }
    }


    private void proceedToInstance(List<Player> participants, boolean isSolo, L2Skill skillUsed) {
        try {
            Player player = participants.get(0);
            if (player == null || !player.isOnline()) {
                return;
            }

            if (skillUsed != null) {
                for (Player member : participants) {
                    if (member == null || !member.isOnline() || member.isDead()) {
                        return;
                    }
                    L2Skill cur = member.getCast().getCurrentSkill();
                    if (member.getCast().isCastingNow() && cur != null && cur.getId() != Config.CHANNELING_SKILL_ID) {
                        String msg = isSolo ? getLocalizedString(member, "12017") : getLocalizedString(member, "12018").replace("{name}", member.getName());
                        participants.forEach(p -> {
                            if (p != null && p.isOnline()) {
                                p.sendMessage(msg);
                                p.abortAll(true);
                            }
                        });
                        return;
                    }
                }
                participants.forEach(member -> {
                    if (member != null && member.isOnline()) {
                        member.stopSkillEffects(skillUsed.getId());
                    }
                });
            }

            for (Player member : participants) {
                if (member == null || !member.isOnline() || member.isDead() || isPlayerInDungeon(member)) {
                    return;
                }
                if (!allowRepeat.get() && getDailyEntries(member.getObjectId()) >= Config.MAX_DAILY_ENTRIES) {
                    showHtml(member, "322-daily-limit.htm");
                    return;
                }
                if (!checkEntryFee(member, isSolo)) {
                    if (!isSolo && player.getParty() != null) {
                        player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka",
                                getLocalizedString(player, "12045").replace("{name}", member.getName())));
                    }
                    return;
                }
            }

            int dungeonId;
            if (isSolo) {
                dungeonId = getSoloDungeonIdForLevel(player.getStatus().getLevel());
                if (dungeonId == -1) {
                    player.sendMessage(getLocalizedString(player, "12019"));
                    return;
                }
            } else {
                int maxLevel = 0;
                for (Player member : participants) {
                    if (member.getStatus().getLevel() > maxLevel) {
                        maxLevel = member.getStatus().getLevel();
                    }
                }
                dungeonId = getPartyDungeonIdForLevel(maxLevel);
                if (dungeonId == -1) {
                    if (player.getParty() != null) {
                        player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka", getLocalizedString(player, "12020")));
                    }
                    return;
                }
            }

            final DungeonTemplate template = DungeonData.getInstance().getDungeon(dungeonId);
            if (template == null) {
                LOGGER.warn("[" + getName() + "] Template de dungeon nao encontrado para ID: " + dungeonId);
                player.sendMessage(getLocalizedString(player, "12021"));
                return;
            }


            if (isSolo) {
                if (Config.SOLO_ENTRY_FEE_ENABLED) {
                    if (!player.destroyItemByItemId(Config.SOLO_ENTRY_FEE_ITEM_ID, Config.SOLO_ENTRY_FEE_ITEM_COUNT, true)) {
                        player.sendMessage(getLocalizedString(player, "12044"));
                        return;
                    }
                }
            } else {
                if (Config.PARTY_ENTRY_FEE_ENABLED) {
                    for (Player member : participants) {
                        member.destroyItemByItemId(Config.PARTY_ENTRY_FEE_ITEM_ID, Config.PARTY_ENTRY_FEE_ITEM_COUNT, true);
                    }
                }
            }

            participants.forEach(p -> incrementDailyEntry(p.getObjectId()));

            KamalokaDungeon dungeon = new KamalokaDungeon(template, participants);
            _dungeons.put(dungeon.getInstanceId(), dungeon);

        } catch (Exception e) {
            LOGGER.error("[" + getName() + "] Problemas ao criar a dungeon: ", e);
        } finally {
            unlockParticipants(participants);
        }
    }

    private void handleLeaveInstance(Player player) {
        Dungeon dungeon = getPlayerDungeon(player);
        if (dungeon instanceof KamalokaDungeon kamaloka) {
            kamaloka.cancelDungeon(getLocalizedString(player, "12025"));
        } else {
            int instId = (player.getInstanceMap() != null) ? player.getInstanceMap().getId() : 0;
            if (instId > 0) {
                LOGGER.info("[" + getName() + "] Jogador " + player.getName() + " saindo via fallback de instancia (instanceId=" + instId + ").");
                player.setIsImmobilized(false);
                DungeonState.set(player, null);
                player.setInstanceMap(InstanceManager.getInstance().getInstance(0), true);
                player.teleToLocation(Config.TELEPORT_EXIT_LOC);
                player.sendMessage(getLocalizedString(player, "12024").replace("{dungeon}", "Kamaloka"));
                player.broadcastCharInfo();
                player.broadcastUserInfo();
            } else {
                player.sendMessage(getLocalizedString(player, "12023"));
            }
        }
    }

    private void handleToggleRepeat(Player player) {
        if (!player.isGM()) {
            player.sendMessage(getLocalizedString(player, "12026"));
            return;
        }
        boolean newState = allowRepeat.compareAndSet(allowRepeat.get(), !allowRepeat.get());
        String status = newState ? getLocalizedString(player, "12028") : getLocalizedString(player, "12029");
        player.sendMessage(getLocalizedString(player, "12027").replace("{status}", status));
    }

    @Override
    public void onDeath(Creature killer, Creature victim) {
        if (victim instanceof Player player) {
            Dungeon dungeon = getPlayerDungeon(player);
            if (dungeon instanceof KamalokaDungeon kd) {
                kd.checkPartyWipe();
            }
        }
    }

    public void onPlayerDeath(Player player, Creature killer) {
        Dungeon dungeon = getPlayerDungeon(player);
        if (dungeon instanceof KamalokaDungeon kd) {
            kd.checkPartyWipe();
        }
    }

    public void onDungeonFinish(KamalokaDungeon dungeon) {
        _dungeons.remove(dungeon.getInstanceId());
        List<Player> players = dungeon.getPlayers();
        Player refPlayer = (players != null && !players.isEmpty()) ? players.get(0) : null;
        if (refPlayer != null) {
            nextInstanceAnnounceTask.set(_executor.schedule(() ->
                    dungeon.broadcastToDungeon(getLocalizedString(refPlayer, "12032").replace("{time}", String.valueOf(INSTANCE_COOLDOWN_MINUTES))),
                    INSTANCE_COOLDOWN_MINUTES, TimeUnit.MINUTES));
        }
    }

    private void showHtml(Player player, String fileName) {
        String content = loadHtmlContent(fileName);
        if (content != null) {
            final NpcHtmlMessage html = new NpcHtmlMessage(0);
            html.setHtml(content);
            player.sendPacket(html);
        } else {
            LOGGER.warn("[" + getName() + "] Falha ao carregar o arquivo HTML: " + fileName);
            player.sendMessage(getLocalizedString(player, "12034"));
        }
    }

    private String loadHtmlContent(String fileName) {
        Path[] candidates = new Path[] {
            Path.of("data/locale/en_US/html/kamaloka/" + fileName),
            Path.of("game/data/locale/en_US/html/kamaloka/" + fileName),
            Path.of("data/html/kamaloka/" + fileName),
            Path.of("game/data/html/kamaloka/" + fileName)
        };

        for (Path p : candidates) {
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (IOException ignored) {}
            }
        }

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mods/kamaloka/htmls/" + fileName)) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {}

        return null;
    }

    private void copyResourceIfNotExists(String resourcePath, String destinationPath) throws IOException {
        Path dest = Path.of(destinationPath);
        if (Files.notExists(dest)) {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
                 OutputStream out = Files.newOutputStream(dest)) {
                if (in == null) {
                    return;
                }
                in.transferTo(out);
                LOGGER.info("[" + getName() + "] Arquivo padrao copiado: " + destinationPath);
            }
        }
    }

    private void shutdownExecutor() {
        if (_executor != null && !_executor.isShutdown()) {
            _executor.shutdown();
            try {
                if (!_executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    _executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                _executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void playerInstanceMapReset(Player player) {
        try {
            player.setInstanceMap(InstanceManager.getInstance().getInstance(0), true);
        } catch (Throwable ignored) {}
    }

    /**
     * Classe interna que gerencia o ciclo de vida e finalizacao da Kamaloka.
     */
    private class KamalokaDungeon extends Dungeon {
        private final int _instanceId = INSTANCE_NAME.hashCode() ^ System.identityHashCode(this);
        private final String _dungeonName;
        private final AtomicBoolean _isCleaningUp = new AtomicBoolean(false);

        public KamalokaDungeon(DungeonTemplate template, List<Player> players) {
            super(template, players);
            _dungeonName = template.name;
        }

        public void onBossKill(Player killer) {
            broadcastToDungeon(getLocalizedString(killer, "12030").replace("{name}", killer.getName()));
            Party party = killer.getParty();
            if (party != null) {
                party.getMembers().stream()
                        .filter(Objects::nonNull)
                        .filter(member -> member.isIn3DRadius(killer, PARTY_RANGE_TO_REWARD))
                        .forEach(this::rewardPlayer);
            } else {
                rewardPlayer(killer);
            }
            cleanupDungeon(false);
        }

        public void checkPartyWipe() {
            List<Player> players = getPlayers();
            if (players == null || players.isEmpty()) {
                cleanupDungeon(true);
                return;
            }
            boolean allDead = players.stream().filter(Objects::nonNull).allMatch(Creature::isDead);
            if (allDead) {
                cleanupDungeon(true);
            }
        }

        private void rewardPlayer(Player player) {
            player.addItem(Config.REWARD_ITEM_ID, (int) Config.REWARD_ITEM_COUNT, true);
        }

        @Override
        public void cancelDungeon() {
            cleanupDungeon(true);
        }

        public void cancelDungeon(String message) {
            broadcastToDungeon(message);
            cleanupDungeon(true);
        }

        private void cancelSuperTasks() {
            String[] taskNames = new String[] {
                "dungeonCancelTask", "timerTask", "monitorTask", "nextTask", "countdownTask"
            };
            for (String name : taskNames) {
                try {
                    Field f = Dungeon.class.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(this);
                    if (val instanceof ScheduledFuture<?> task) {
                        task.cancel(true);
                    }
                    f.set(this, null);
                } catch (Throwable ignored) {}
            }
        }

        private void deleteSuperInstanceAndMobs() {
            try {
                Field instField = Dungeon.class.getDeclaredField("instance");
                instField.setAccessible(true);
                Object val = instField.get(this);
                if (val instanceof MapInstance mapInst) {
                    InstanceManager.getInstance().deleteInstance(mapInst.getId());
                }
            } catch (Throwable t) {
                LOGGER.warn("[" + getName() + "] Falha ao deletar MapInstance da superclasse: " + t.getMessage());
            }

            try {
                Field mobsField = Dungeon.class.getDeclaredField("mobToTemplate");
                mobsField.setAccessible(true);
                Object val = mobsField.get(this);
                if (val instanceof Map<?, ?> mobsMap) {
                    for (Object k : mobsMap.keySet()) {
                        if (k instanceof Attackable mob) {
                            deleteMob(mob);
                        }
                    }
                    mobsMap.clear();
                }
            } catch (Throwable ignored) {}
        }

        private void deleteMob(Attackable mob) {
            try {
                if (mob == null) return;
                ASpawn spawn = mob.getSpawn();
                if (spawn != null) {
                    spawn.setRespawnDelay(0);
                    if (spawn.getNpc() != null) {
                        spawn.getNpc().deleteMe();
                    }
                    if (spawn instanceof Spawn s) {
                        SpawnManager.getInstance().deleteSpawn(s);
                    }
                } else {
                    mob.deleteMe();
                }
            } catch (Throwable ignored) {}
        }

        private void cleanupDungeon(boolean failed) {
            if (!_isCleaningUp.compareAndSet(false, true)) {
                return;
            }


            cancelSuperTasks();

            List<Player> participants = getPlayers();
            if (failed && participants != null && !participants.isEmpty()) {
                Player firstPlayer = participants.get(0);
                if (firstPlayer != null) {
                    broadcastToDungeon(getLocalizedString(firstPlayer, "12031").replace("{dungeon}", _dungeonName));
                }
            }

            if (participants != null) {
                for (Player p : participants) {
                    if (p != null && p.isOnline()) {

                        try {
                            p.sendPacket(new ExShowScreenMessage("", 1, SMPOS.BOTTOM_RIGHT, false));
                        } catch (Throwable ignored) {}


                        if (p.isDead()) {
                            try {
                                p.doRevive();
                                p.getStatus().setHpMp(p.getStatus().getMaxHp(), p.getStatus().getMaxMp());
                            } catch (Throwable ignored) {}
                        }

                        p.setIsImmobilized(false);
                        DungeonState.set(p, null);
                        playerInstanceMapReset(p);
                        p.teleToLocation(Config.TELEPORT_EXIT_LOC);
                        p.sendMessage(getLocalizedString(p, failed ? "12031" : "12024").replace("{dungeon}", _dungeonName));
                        p.broadcastCharInfo();
                        p.broadcastUserInfo();
                    }
                }
            }


            deleteSuperInstanceAndMobs();

            try {
                DungeonManager.getInstance().removeDungeon(this);
            } catch (Exception e) {
                LOGGER.warn("[" + getName() + "] Erro ao remover dungeon do DungeonManager: " + e.getMessage());
            }

            KamalokaInstancia.getInstance().onDungeonFinish(this);
        }

        public int getInstanceId() {
            return _instanceId;
        }

        public void broadcastToDungeon(String message) {
            List<Player> players = getPlayers();
            if (players == null || players.isEmpty()) return;
            for (Player p : players) {
                if (p != null && p.isOnline()) {
                    p.sendPacket(new CreatureSay(0, SayType.TELL, "Kamaloka", message));
                }
            }
        }
    }
}
