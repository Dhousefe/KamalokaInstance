package mods.dhousefe.kamaloka;

import ext.mods.InstanceMap.InstanceManager;
import ext.mods.InstanceMap.MapInstance;
import ext.mods.commons.logging.CLogger;
import ext.mods.dungeon.Dungeon;
import ext.mods.dungeon.DungeonManager;
import ext.mods.dungeon.DungeonTemplate;
import ext.mods.dungeon.data.DungeonData;
import ext.mods.dungeon.enums.DungeonType;
import ext.mods.extensions.interfaces.L2JExtension;
import ext.mods.extensions.listener.OnKillListener;
import ext.mods.extensions.listener.command.OnBypassCommandListener;
import ext.mods.extensions.listener.manager.BypassCommandManager;
import ext.mods.extensions.listener.manager.CreatureListenerManager;
import ext.mods.extensions.listener.manager.NpcListenerManager;
import ext.mods.extensions.listener.manager.PlayerListenerManager;
import ext.mods.extensions.listener.actor.npc.OnInteractListener;
import ext.mods.gameserver.data.xml.NpcData;
import ext.mods.gameserver.enums.MessageType;
import ext.mods.gameserver.model.group.Party;
import ext.mods.gameserver.model.actor.Creature;
import ext.mods.gameserver.model.actor.Npc;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.gameserver.model.location.Location;
//import ext.mods.gameserver.model.location.MapRegion;
import ext.mods.gameserver.model.location.SpawnLocation;
import ext.mods.gameserver.model.spawn.Spawn;
import ext.mods.gameserver.network.serverpackets.CreatureSay;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.network.serverpackets.NpcHtmlMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Mod Kamaloka Instancia.
 *
 * Compatível com o ambiente compilado em server.jar.
 * Refatorado para maior integração com o sistema de Dungeon.
 *
 * @author Dhousefe
 * @version 4.2
 */
public final class KamalokaInstancia implements L2JExtension, OnBypassCommandListener, OnInteractListener {
    private static final CLogger LOGGER = new CLogger(KamalokaInstancia.class.getName());

    // --- Configurações ---
    private static final String INSTANCE_NAME = "KamalokaInstancia";
    //private static final MapInstance INSTANCE = new MapInstance(10);
    
    private static final int NPC_ID = 55020;
    private static final int BOSS_ID = 55021;
    private static final String BYPASS_PREFIX = "kamaloka_";

    // --- ID da Dungeon no XML ---
    private static final int KAMALOKA_SOLO_DUNGEON_ID = 10; // ID da sua dungeon "Heretic HexTec"
    private static final int KAMALOKA_PARTY_DUNGEON_ID = 11; // ID da sua dungeon "Team Trial"

    // --- Configurações da Instância ---
    private static final int INSTANCE_COOLDOWN_MINUTES = 320;
    private static final int INSTANCE_DURATION_MINUTES = 30;
    private static final int PARTY_RANGE_TO_REWARD = 1500;
    private static final Location TELEPORT_ENTER_LOC = new Location(86708, 257918, -11671);
    private static final Location TELEPORT_EXIT_LOC = new Location(83425, 148585, -3406);
    private static final SpawnLocation BOSS_SPAWN_LOC = new SpawnLocation(86951, 258458, -11672, 2412);

    // --- Recompensas ---
    private static final int REWARD_ITEM_ID = 4037;
    private static final int REWARD_ITEM_COUNT = 15;

    // --- Estado do Manager ---
    private static final AtomicBoolean allowRepeat = new AtomicBoolean(true);
    private final ConcurrentHashMap<Integer, Long> _playerEntryTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, KamalokaDungeon> _dungeons = new ConcurrentHashMap<>();

    // --- Tarefas Agendadas ---
    private final AtomicReference<ScheduledFuture<?>> nextInstanceAnnounceTask = new AtomicReference<>();
    private ScheduledExecutorService _executor;

    private static class SingletonHolder {
        private static final KamalokaInstancia INSTANCE = new KamalokaInstancia();
    }

    public static KamalokaInstancia getInstance() {
        return SingletonHolder.INSTANCE;
    }

    @Override
    public void onLoad() {
        try {
            copyResourceIfNotExists("mods/kamaloka/xmls/Kamaloka_Dungeon.xml", "./data/custom/mods/Kamaloka_Dungeon.xml");
            // Carrega as dungeons do seu arquivo XML customizado
            DungeonData.getInstance().parseDataFile("custom/mods/Kamaloka_Dungeon.xml");
            

        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar ou carregar o arquivo Kamaloka_Dungeon.xml.", e);
            return;
        }
        try {
            copyResourceIfNotExists("mods/kamaloka/xmls/Kamaloka.xml", "./data/xml/npcs/kamaloka.xml");

        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {
            copyResourceIfNotExists("mods/kamaloka/htmls/322.htm", "./data/locale/en_US/html/kamaloka/322.htm");

        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-busy.htm", "./data/locale/en_US/html/kamaloka/322-busy.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-completed.htm", "./data/locale/en_US/html/kamaloka/322-completed.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-daily-limit.htm", "./data/locale/en_US/html/kamaloka/322-daily-limit.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-inprogress.htm", "./data/locale/en_US/html/kamaloka/322-inprogress.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-no-party.htm", "./data/locale/en_US/html/kamaloka/322-no-party.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        try {

            copyResourceIfNotExists("mods/kamaloka/htmls/322-teleported.htm", "./data/locale/en_US/html/kamaloka/322-teleported.htm");
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar arquivos de configuração padrão.", e);
            return;
        }
        _executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        BypassCommandManager.getInstance().registerBypassListener(this);


        LOGGER.info("[" + getName() + "] Carregado e ativado com sucesso.");
    }

    @Override
    public void onDisable() {
        BypassCommandManager.getInstance().unregisterBypassListener(this);
        _dungeons.values().forEach(d -> {
            if (d instanceof KamalokaDungeon kd) {
                kd.cancelDungeon("O servidor está sendo desligado.");
            }
        });
        shutdownExecutor();
        LOGGER.info("[" + getName() + "] Descarregado.");
    }

    @Override
    public String getName() {
        return INSTANCE_NAME;
    }

    
    /*@Override 
    public boolean onInteract(Npc npc, Player player) {
        if (npc.getNpcId() != NPC_ID) return true;
        showHtml(player, "322.htm");
        return false;
    }*/

    @Override
	public boolean onInteract(Npc npc, Player player)
	{
		if (npc.getNpcId() == 55020)
		{
			showHtml(player, "322.htm");
		}
		
		return false;
	}

    @Override
    public boolean onBypass(Player player, String command) {
        if (command.equals("bp_showMarketPlace")) {
            showHtml(player, "322.htm");
            return true;
        }

        if (!command.startsWith(BYPASS_PREFIX)) return false;

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

    private void handleEnterInstance(Player player, boolean isSolo) {
        if (player.getDungeon() != null) {
            player.sendMessage("Você já está em uma dungeon.");
            return;
        }

        if (!allowRepeat.get()) {
            long lastEntry = _playerEntryTimes.getOrDefault(player.getObjectId(), 0L);
            if (isToday(lastEntry)) {
                showHtml(player, "322-daily-limit.htm");
                return;
            }
        }

        final int dungeonId = isSolo ? KAMALOKA_SOLO_DUNGEON_ID : KAMALOKA_PARTY_DUNGEON_ID;
        final DungeonTemplate template = DungeonData.getInstance().getDungeon(dungeonId);

        if (template == null) {
            LOGGER.warn("[" + getName() + "] Tentativa de entrar na dungeon com ID " + dungeonId + ", mas o template não foi encontrado.");
            player.sendMessage("A configuração para esta dungeon não foi encontrada. Contate um administrador.");
            return;
        }

        List<Player> participants;
        if (isSolo) {
            participants = Collections.singletonList(player);
        } else {
            final Party party = player.getParty();
            if (party == null) {
                showHtml(player, "322-no-party.htm");
                return;
            }
            participants = party.getMembers();
        }
        try {
            long currentTime = System.currentTimeMillis();

            participants.forEach(p -> _playerEntryTimes.put(p.getObjectId(), currentTime));

            KamalokaDungeon dungeon = new KamalokaDungeon(template, participants);

            _dungeons.put(dungeon.getInstanceId(), dungeon);

            showHtml(player, "322-teleported.htm");
        } catch (Exception e) {
            LOGGER.info("[" + getName() + "] Problemas ao criar a dungeon: " + e.getMessage());

        }

    }

    private void copyResourceIfNotExists(String resourcePath, String destinationPath) throws IOException {
        Path dest = Path.of(destinationPath);
        if (Files.notExists(dest)) {
            Files.createDirectories(dest.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
                 OutputStream out = Files.newOutputStream(dest)) {
                if (in == null) {
                    throw new IOException("Recurso não encontrado no JAR: " + resourcePath);
                }
                in.transferTo(out);
                LOGGER.info("[" + getName() + "] Arquivo de configuração padrão criado: " + destinationPath);
            }
        }
    }

    private boolean isToday(long timestamp) {
        if (timestamp == 0) return false;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        return sdf.format(new Date(timestamp)).equals(sdf.format(new Date()));
    }

    /*private void handleLeaveInstance(Player player) {
        if (player.getDungeon() instanceof KamalokaDungeon) {
            
            
            player.setIsImmobilized(false);
            teleportPlayer(player, TELEPORT_EXIT_LOC, "Você saiu de Kamaloka.", 1);
            player.broadcastCharInfo();
            player.broadcastUserInfo();
            player.setDungeon((Dungeon)null);
            player.setInstanceMap(InstanceManager.getInstance().getInstance(0), true);
            
            
        } else {
            player.sendMessage("Você não está em Kamaloka.");
        }
    }*/


    private void handleLeaveInstance(Player player) {
        Dungeon dungeon = player.getDungeon();
        if (dungeon instanceof KamalokaDungeon) {
            // Converte para o nosso tipo de dungeon
            KamalokaDungeon kamaloka = (KamalokaDungeon) dungeon;
            // Chama o método de cancelamento que fará toda a limpeza e teleportará todos os jogadores para fora.
            kamaloka.cancelDungeon("A instância foi encerrada por um jogador.");
            player.setIsImmobilized(false);
            teleportPlayer(player, TELEPORT_EXIT_LOC, "Você saiu de Kamaloka.", 1);
            player.setDungeon((Dungeon)null);
            player.setInstanceMap(InstanceManager.getInstance().getInstance(0), true);
            player.broadcastCharInfo();
            player.broadcastUserInfo();
        } else {
            player.sendMessage("Você não está em Kamaloka.");
        }
    }

    private void handleToggleRepeat(Player player) {
        if (!player.isGM()) {
            player.sendMessage("Você não tem permissão para usar este comando.");
            return;
        }
        boolean newState = allowRepeat.compareAndSet(allowRepeat.get(), !allowRepeat.get());
        String status = newState ? "habilitada" : "restrita a uma vez por dia";
        player.sendMessage("A reentrada em Kamaloka agora está " + status + ".");
    }

    public void onKill(Creature attacker, Creature victim) {
        if (!(victim instanceof Npc npc) || npc.getNpcId() != BOSS_ID) return;

        Player killer = attacker.getActingPlayer();
        if (killer == null || !(killer.getDungeon() instanceof KamalokaDungeon)) return;

        KamalokaDungeon dungeon = (KamalokaDungeon) killer.getDungeon();
        dungeon.onBossKill(killer);
    }

    public void onPlayerDeath(Player player, Creature killer) {
        if (player.getDungeon() instanceof KamalokaDungeon) {
            KamalokaDungeon dungeon = (KamalokaDungeon) player.getDungeon();
            dungeon.checkPartyWipe();
        }
    }

    public void onDungeonFinish(KamalokaDungeon dungeon) {
        _dungeons.remove(dungeon.getInstanceId());
        nextInstanceAnnounceTask.set(_executor.schedule(() ->
            dungeon.broadcastToDungeon("Tempo restante para derrotar o chefe: " + INSTANCE_COOLDOWN_MINUTES + " minuto(s)."),
            
            INSTANCE_COOLDOWN_MINUTES, TimeUnit.MINUTES));
    }

    private void teleportPlayer(Player player, Location loc, String message, long delayMs) {
        _executor.schedule(() -> {
            if (player != null && player.isOnline()) {
                player.teleToLocation(loc);
                if (message != null && !message.isEmpty()) player.sendMessage(message);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void showHtml(Player player, String fileName) {
        try {
            final Path path = Path.of("data/locale/en_US/html/kamaloka/" + fileName);
            String content = Files.readString(path);
            final NpcHtmlMessage html = new NpcHtmlMessage(NPC_ID);
            html.setHtml(content);
            player.sendPacket(html);
        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao carregar o arquivo HTML: " + fileName, e);
            player.sendMessage("Erro: não foi possível carregar a janela de diálogo.");
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

    /**
     * Classe interna que representa a instância Kamaloka, herdando de Dungeon.
     */
    private class KamalokaDungeon extends Dungeon {
        //private final MapInstance _instance;
        private final int _instanceId = INSTANCE_NAME.hashCode() ^ System.identityHashCode(this);

        public KamalokaDungeon(DungeonTemplate template, List<Player> players) {
            super(template, players); // Agora usa o template carregado do XML
            //_instance = InstanceManager.getInstance().createInstance();
            // A lógica de startDungeon() da classe pai (Dungeon) será chamada automaticamente.
        }

        

        /*private void startDungeon() {
            try (ExecutorService playerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = getPlayers().stream()
                    .map(player -> CompletableFuture.runAsync(() -> {
                        player.setDungeon(this);
                        player.setInstanceMap(_instance, true);
                        player.setIsImmobilized(true);
                        KamalokaInstancia.getInstance().teleportPlayer(player, TELEPORT_ENTER_LOC, "Você foi teleportado para Kamaloka.", 1000);
                        KamalokaInstancia.getInstance()._executor.schedule(() -> player.setIsImmobilized(false), 3, TimeUnit.SECONDS);
                    }, playerExecutor))
                    .collect(Collectors.toList());

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                    KamalokaInstancia.getInstance()._executor.schedule(this::spawnBoss, 5, TimeUnit.SECONDS);
                });
            }
        }*/

        protected void beginTeleport() {
            // Sobrescreve o método para evitar que a lógica da classe pai (que depende do template) seja executada.
        }

        /*private void spawnBoss() {
            try {
                if (_instance == null) {
                    LOGGER.error("[KamalokaDungeon] Não foi possível obter a MapInstance da Dungeon.");
                    cleanupDungeon(true);
                    return;
                }

                final Spawn spawn = new Spawn(NpcData.getInstance().getTemplate(BOSS_ID));
                spawn.setLoc(BOSS_SPAWN_LOC);
                spawn.setRespawnDelay(100000);
                _boss = spawn.doSpawn(false);
                _boss.setInstanceMap(_instance, false);
                //spawn.stopRespawn();

                broadcastToDungeon("O chefe de Kamaloka apareceu!");
                _remainingSeconds.set(INSTANCE_DURATION_MINUTES * 60);

                _bossDespawnTask.set(KamalokaInstancia.getInstance()._executor.schedule(() -> {
                    broadcastToDungeon("O tempo acabou! O chefe de Kamaloka desapareceu.");
                    cleanupDungeon(true);
                }, INSTANCE_DURATION_MINUTES, TimeUnit.MINUTES));

                _warningTask.set(KamalokaInstancia.getInstance()._executor.scheduleAtFixedRate(this::warnTimeLeft, 1, 1, TimeUnit.MINUTES));
            } catch (Exception e) {
                LOGGER.error("[KamalokaDungeon] Falha ao spawnar o boss.", e);
                cleanupDungeon(true);
            }
        }*/

        
        public void onBossKill(Player killer) {
            broadcastToDungeon("O chefe de Kamaloka foi derrotado por " + killer.getName() + "!");

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
            boolean allDead = getPlayers().stream().allMatch(Creature::isDead);
            if (allDead) {
                cleanupDungeon(true);
            }
        }

        private void rewardPlayer(Player player) {
            player.addItem(REWARD_ITEM_ID, REWARD_ITEM_COUNT, true);
        }

        
        private void cleanupDungeon(boolean failed) {
            if (failed) {
                broadcastToDungeon("A incursão em Kamaloka falhou.");
                cancelDungeon();
            }

            getPlayers().forEach(p -> {
                if (p != null && p.isOnline()) {
                    KamalokaInstancia.getInstance().teleportPlayer(p, TELEPORT_EXIT_LOC, "Você foi retornado de Kamaloka.", 1);
                    p.setDungeon(null);
                    p.setInstanceMap(null, true);
                    p.setIsImmobilized(false);
                }
            });

            /*if (_boss != null && !_boss.isDead()) {
                _boss.deleteMe();
            }

            cancelFuture(_bossDespawnTask);
            cancelFuture(_warningTask);*/
            
            /*if (_instance != null) {
                InstanceManager.getInstance().deleteInstance(_instance.getId());
            }*/

            DungeonManager.getInstance().removeDungeon(this);
            KamalokaInstancia.getInstance().onDungeonFinish(this);

            cancelDungeon();
            
        }

        /*private void warnTimeLeft() {
            int secondsLeft = _remainingSeconds.addAndGet(-60);
            if (secondsLeft > 0) {
                int minutesLeft = secondsLeft / 60;
                broadcastToDungeon("Tempo restante para derrotar o chefe: " + minutesLeft + " minuto(s).");
            }
        }*/

        public int getInstanceId() {
            return _instanceId;
        }

        private void broadcastToDungeon(String message) {
            getPlayers().forEach(p -> {
                if (p != null && p.isOnline()) {
                    p.sendPacket(new CreatureSay(0, SayType.TELL, "Kamaloka", message));
                }
            });
        }

        
        public void cancelDungeon(String message) {
            broadcastToDungeon(message);
            cleanupDungeon(true);
        }

        private void cancelFuture(AtomicReference<ScheduledFuture<?>> futureRef) {
            ScheduledFuture<?> future = futureRef.getAndSet(null);
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }
}