package mods.dhousefe.kamaloka;

import ext.mods.InstanceMap.InstanceManager;
import ext.mods.commons.logging.CLogger;
import ext.mods.dungeon.Dungeon;
import ext.mods.dungeon.DungeonManager;
import ext.mods.dungeon.DungeonTemplate;
import ext.mods.dungeon.data.DungeonData;
import ext.mods.dungeon.holder.SpawnTemplate;
import ext.mods.extensions.interfaces.L2JExtension;
import ext.mods.extensions.listener.command.OnBypassCommandListener;
import ext.mods.extensions.listener.manager.BypassCommandManager;
import ext.mods.extensions.listener.actor.npc.OnInteractListener;
import ext.mods.gameserver.data.SkillTable;
import ext.mods.gameserver.data.xml.NpcData;
import ext.mods.gameserver.model.actor.template.NpcTemplate;
import ext.mods.gameserver.model.group.Party;
import ext.mods.gameserver.model.actor.Creature;
import ext.mods.gameserver.model.actor.Npc;
import ext.mods.gameserver.model.actor.Player;
import ext.mods.gameserver.model.location.Location;
import ext.mods.gameserver.network.serverpackets.CreatureSay;
import ext.mods.gameserver.enums.SayType;
import ext.mods.gameserver.network.serverpackets.NpcHtmlMessage;
import ext.mods.gameserver.model.actor.template.CreatureTemplate;
import ext.mods.gameserver.skills.L2Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.lang.reflect.Field;

/**
 * Mod Kamaloka Instancia.
 *
 * Compatível com o ambiente compilado em server.jar.
 * Refatorado para maior integração com o sistema de Dungeon.
 *
 * @author Dhousefe
 * @version 4.2
 */
public final class KamalokaInstancia implements L2JExtension, OnBypassCommandListener {
    private static final CLogger LOGGER = new CLogger(KamalokaInstancia.class.getName());

    // --- Configurações ---
    private static final String INSTANCE_NAME = "KamalokaInstancia";
    private static final String BYPASS_PREFIX = "kamaloka_";

    // --- Configurações da Instância ---
    private static final int INSTANCE_COOLDOWN_MINUTES = 320;
    private static final int PARTY_RANGE_TO_REWARD = 1500;

    // --- Estado do Manager ---
    private static final AtomicBoolean allowRepeat = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, List<Long>> _playerEntryTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, KamalokaDungeon> _dungeons = new ConcurrentHashMap<>();
    // --- Cache de Status Originais ---
    private final Map<Integer, OriginalStats> _originalNpcStatsCache = new ConcurrentHashMap<>();

    // --- Tarefas Agendadas ---
    private final AtomicReference<ScheduledFuture<?>> nextInstanceAnnounceTask = new AtomicReference<>();
    private ScheduledExecutorService _executor;

    private static class SingletonHolder {
        private static final KamalokaInstancia INSTANCE = new KamalokaInstancia();
    }

    /**
     * Classe interna para armazenar os status originais de um NpcTemplate.
     * Os campos nao sao estaticos para armazenar os valores de cada NPC individualmente.
     */
    private static class OriginalStats {
        private final byte level;
        private final double baseHpMax;
        private final double basePDef;
        private final double baseMDef;
        private final double basePAtk;
        private final double baseMAtk;

        public OriginalStats(NpcTemplate template) {
            byte tempLevel = 0;
            try {
                Field levelField = NpcTemplate.class.getDeclaredField("_level");
                levelField.setAccessible(true);
                tempLevel = levelField.getByte(template);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                LOGGER.error("Nao foi possivel acessar o campo _level via reflection.", e);
            }
            this.level = tempLevel;
            this.baseHpMax = template._baseHpMax;
            this.basePDef = template._basePDef;
            this.baseMDef = template._baseMDef;
            this.basePAtk = template._basePAtk;
            this.baseMAtk = template._baseMAtk;
        }

        // Getters para os campos
        public byte getLevel() { return level; }
        public double getBaseHpMax() { return baseHpMax; }
        public double getBasePDef() { return basePDef; }
        public double getBaseMDef() { return baseMDef; }
        public double getBasePAtk() { return basePAtk; }
        public double getBaseMAtk() { return baseMAtk; }
    }

    public static KamalokaInstancia getInstance() {
        return SingletonHolder.INSTANCE;
    }

    @Override
    public void onLoad() {
        try {
            copyResourceIfNotExists("mods/kamaloka/configs/kamaloka.properties", "./config/kamaloka.properties");

        } catch (IOException e) {
            LOGGER.warn("[" + getName() + "] Falha ao criar ou carregar o arquivo kamaloka.properties.", e);
            return;
        }
        try {
            
            copyResourceIfNotExists("mods/kamaloka/xmls/Kamaloka_Dungeon.xml", "./data/custom/mods/Kamaloka_Dungeon.xml");
            
            Config.load();
            DungeonData.getInstance().parseDataFile("custom/mods/Kamaloka_Dungeon.xml");
            cacheAllKamalokaNpcStats();
            modifyDungeonSpawns();
            
            

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

    /**
     * Itera por todas as dungeons de Kamaloka e armazena os status originais
     * de cada NPC encontrado em um cache para uso posterior.
     */
    private void cacheAllKamalokaNpcStats() {
        List<Integer> dungeonIds = new ArrayList<>();
        for (int level = 20; level <= 80; level += 5) {
            int soloId = getSoloDungeonIdForLevel(level);
            if (soloId != -1) dungeonIds.add(soloId);
            
            int partyId = getPartyDungeonIdForLevel(level);
            if (partyId != -1) dungeonIds.add(partyId);
        }

        for (int dungeonId : dungeonIds) {
            DungeonTemplate dungeonTemplate = DungeonData.getInstance().getDungeon(dungeonId);
            if (dungeonTemplate == null) continue;

            for (List<SpawnTemplate> spawnList : dungeonTemplate.spawns.values()) {
                for (SpawnTemplate spawn : spawnList) {
                    // Se o NPC ainda nao esta no cache, adiciona-o
                    _originalNpcStatsCache.computeIfAbsent(spawn.npcId, id -> {
                        NpcTemplate template = NpcData.getInstance().getTemplate(id);
                        if (template != null) {
                            return new OriginalStats(template);
                        }
                        LOGGER.warn("[" + getName() + "] Nao foi possivel encontrar o template para o NPC ID " + id + " para cache.");
                        return null;
                    });
                }
            }
        }
        LOGGER.info("[" + getName() + "] Cache de " + _originalNpcStatsCache.size() + " status de NPCs originais foi criado.");
    }

    /**
     * Restaura os status de um NpcTemplate para os valores originais armazenados no cache.
     * @param template O NpcTemplate a ser restaurado.
     */
    private void resetTemplateToOriginal(NpcTemplate template) {
        OriginalStats original = _originalNpcStatsCache.get(template.getNpcId());
        if (original == null) {
            LOGGER.warn("[" + getName() + "] Nao foi possivel encontrar status originais no cache para o NPC ID: " + template.getNpcId());
            return;
        }

        try {
            setNpcLevel(template, original.getLevel());
            template._baseHpMax = original.getBaseHpMax();
            template._basePDef = original.getBasePDef();
            template._baseMDef = original.getBaseMDef();
            template._basePAtk = original.getBasePAtk();
            template._baseMAtk = original.getBaseMAtk();
        } catch (Exception e) {
            LOGGER.error("[" + getName() + "] Falha ao resetar template para o original: NPC ID " + template.getNpcId(), e);
        }
    }



private void modifyDungeonSpawns() {
        List<Integer> dungeonIds = new ArrayList<>();
        for (int level = 20; level <= 80; level += 5) {
            int soloId = getSoloDungeonIdForLevel(level);
            if (soloId != -1) dungeonIds.add(soloId);
            
            int partyId = getPartyDungeonIdForLevel(level);
            if (partyId != -1) dungeonIds.add(partyId);
        }
    
        for (int dungeonId : dungeonIds) {
            DungeonTemplate dungeonTemplate = DungeonData.getInstance().getDungeon(dungeonId);
            if (dungeonTemplate == null) continue;
    
            int baseLevel = getBaseLevelForDungeon(dungeonId);
            if (baseLevel == 0) continue;
    
            // Define os valores de referência para HP e Nível com base nas configurações e no nível da dungeon.
            // Isso garante que todos os monstros da sala tenham os mesmos stats base.
            double soloReferenceHp = 0;
            int soloReferenceLevel = 0;
            double partyReferenceHp = 0;
            int partyReferenceLevel = 0;
            
            // Procura um monstro normal de cada tipo para pegar o valor base e calcular o valor de referência.
            // Isso previne que a logica de HP/Level seja baseada em um monstro especifico do template.
            for (List<SpawnTemplate> spawnList : dungeonTemplate.spawns.values()) {
                for (SpawnTemplate spawn : spawnList) {
                    if (soloReferenceHp == 0 && "[Kamaloka Monster]".equals(spawn.title)) {
                        NpcTemplate template = NpcData.getInstance().getTemplate(spawn.npcId);
                        if (template != null) {
                            soloReferenceLevel = baseLevel + Config.SOLO_MONSTER_LEVEL_BONUS;
                            soloReferenceHp = template._baseHpMax * Config.SOLO_MONSTER_HP_MULTIPLIER;
                        }
                    }
                    if (partyReferenceHp == 0 && "[Kamaloka Party]".equals(spawn.title)) {
                        NpcTemplate template = NpcData.getInstance().getTemplate(spawn.npcId);
                        if (template != null) {
                            partyReferenceLevel = baseLevel + Config.PARTY_MONSTER_LEVEL_BONUS;
                            partyReferenceHp = template._baseHpMax * Config.PARTY_MONSTER_HP_MULTIPLIER;
                        }
                    }
                }
            }
    
            // Se nao encontrou nenhum monstro normal para calcular as referencias, pula para a proxima dungeon.
            if (soloReferenceHp == 0 && partyReferenceHp == 0) continue;
    
            // Segunda passagem: aplicar os stats calculados a todos os NPCs.
            for (List<SpawnTemplate> spawnList : dungeonTemplate.spawns.values()) {
                for (SpawnTemplate spawn : spawnList) {
                    NpcTemplate template = NpcData.getInstance().getTemplate(spawn.npcId);
                    if (template == null) continue;
    
                    // Restaura o template para os valores originais do cache.
                    resetTemplateToOriginal(template);
    
                    try {
                        if ("[Kamaloka Monster]".equals(spawn.title) && soloReferenceHp > 0) {
                            // Seta o nivel e HP para os valores de referencia.
                            setNpcLevel(template, (byte) soloReferenceLevel);
                            setNpcHp(template, soloReferenceHp);
                            
                            // Aplica os outros multiplicadores.
                            template._basePDef *= Config.SOLO_MONSTER_PDEF_MULTIPLIER;
                            template._baseMDef *= Config.SOLO_MONSTER_MDEF_MULTIPLIER;
                        } else if ("[Kamaloka Party]".equals(spawn.title) && partyReferenceHp > 0) {
                            // Seta o nivel e HP para os valores de referencia.
                            setNpcLevel(template, (byte) partyReferenceLevel);
                            setNpcHp(template, partyReferenceHp);
                            
                            template._basePDef *= Config.PARTY_MONSTER_PDEF_MULTIPLIER;
                            template._baseMDef *= Config.PARTY_MONSTER_MDEF_MULTIPLIER;
                        } else if ("[Kamaloka Raid]".equals(spawn.title)) {
                            // Apenas aplica os multiplicadores nos stats do Raid Boss.
                            template._basePAtk *= Config.SOLO_RAID_PATK_MULTIPLIER;
                            template._baseMAtk *= Config.SOLO_RAID_MATK_MULTIPLIER;
                            template._baseMDef *= Config.SOLO_RAID_MDEF_MULTIPLIER;
                            template._basePDef *= Config.SOLO_RAID_PDEF_MULTIPLIER;
                        } else if ("[Kamaloka Raid Party]".equals(spawn.title)) {
                            // Apenas aplica os multiplicadores nos stats do Raid Boss.
                            template._basePAtk *= Config.PARTY_RAID_PATK_MULTIPLIER;
                            template._baseMAtk *= Config.PARTY_RAID_MATK_MULTIPLIER;
                            template._baseMDef *= Config.PARTY_RAID_MDEF_MULTIPLIER;
                            template._basePDef *= Config.PARTY_RAID_PDEF_MULTIPLIER;
                        }
                    } catch (Exception e) {
                        LOGGER.error("[" + getName() + "] Falha ao modificar stats para NPC ID " + spawn.npcId, e);
                    }
                }
            }
        }
    }

   

    // --- Metodos auxiliares para Reflection ---
    private void setNpcLevel(CreatureTemplate template, byte level) throws NoSuchFieldException, IllegalAccessException {
        Field levelField = NpcTemplate.class.getDeclaredField("_level");
        levelField.setAccessible(true);
        levelField.setByte(template, level);
        //LOGGER.error("[" + getName() + "] setNpcLevel() " + level);
    }

    private void setNpcHp(CreatureTemplate template, double hp) throws NoSuchFieldException, IllegalAccessException {
        Field hpField = CreatureTemplate.class.getDeclaredField("_baseHpMax");
        hpField.setAccessible(true);
        hpField.setDouble(template, hp);
        //LOGGER.error("[" + getName() + "] setNpcHp() " + hp);
    }

    

    /**
     * ALTERAÇÃO: Novo método para calcular o ID da dungeon solo com base no nível do jogador.
     * @param level O nível do jogador.
     * @return O ID da dungeon correspondente ou -1 se o nível for inválido.
     */
    private int getSoloDungeonIdForLevel(int level) {
        if (level < 20 || level > 80) {
            return -1; // Fora da faixa de níveis permitida
        }
        // Fórmula para mapear níveis para IDs (20-24 -> 10, 25-29 -> 11, etc.)
        return 10 + ((level - 20) / 5);
    }

    private int getPartyDungeonIdForLevel(int level) {
        if (level < 20 || level > 80) return -1;
        return 22 + ((level - 20) / 5);
    }

    private int getBaseLevelForDungeon(int dungeonId) {
        if (dungeonId >= 10 && dungeonId < 22) { // Solo Dungeons
            return ((dungeonId - 10) * 5) + 20;
        } else if (dungeonId >= 22) { // Party Dungeons
            return ((dungeonId - 22) * 5) + 20;
        }
        return 0;
    }


    private void handleEnterInstance(Player player, boolean isSolo) {
        // Lógica de canalização com verificação de combate
        
        try {
            final L2Skill channelingSkill = SkillTable.getInstance().getInfo(Config.CHANNELING_SKILL_ID, 1);
            if (channelingSkill == null) {
                LOGGER.warn("[" + getName() + "] Skill de canalização não encontrada: " + Config.CHANNELING_SKILL_ID);
                player.sendMessage("Ocorreu um erro ao tentar entrar na instância.");
                proceedToInstance(Collections.singletonList(player), isSolo, null);
                return;
            }
            
            final int castTime = channelingSkill.getHitTime() + 1;
            List<Player> participants;
            if (isSolo) {
                if (!checkPlayerRestrictions(player)){
                    
                 return;
                }
                player.abortAll(true);
                player.getCast().doCast(channelingSkill, player, null);
                participants = Collections.singletonList(player);
                
                player.sendMessage("Preparando para entrar na instância...");
                //participants.forEach(p -> player.getCast().doCast(channelingSkill, player, null));
                _executor.schedule(() -> proceedToInstance(participants, true, channelingSkill), castTime, TimeUnit.MILLISECONDS);

            } else { // Modo Party
                final Party party = player.getParty();
                if (party == null) {
                    showHtml(player, "322-no-party.htm");
                    return;
                }
                participants = party.getMembers();
                
                // Verifica as restrições para todos os membros da party
                for (Player member : participants) {
                    
                    if (!checkPlayerRestrictions(member)) {
                        party.broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka Party", "Um membro da party (" + member.getName() + ") não cumpre os requisitos."));
                        return;
                    }
                    member.abortAll(true);
                }
                
                // Inicia a canalização para todos os membros
                party.broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Kamaloka Party", "Todos os membros devem permanecer parados para entrar na instância..."));
                participants.forEach(member -> member.getCast().doCast(channelingSkill, member, null));
                
                _executor.schedule(() -> proceedToInstance(participants, false, channelingSkill), castTime, TimeUnit.MILLISECONDS);
            }

        } catch (Exception e) {
            LOGGER.error("[" + getName() + "] Erro durante a canalização para a instância: ", e);
        }
    }

    private boolean checkPlayerRestrictions(Player player) {
        if (player.getDungeon() != null) {
            player.sendMessage("Você já está em uma dungeon.");
            return false;
        }
                
        // Verificações de restrição
        if (player.isInCombat()) {
            player.sendMessage("Você não pode entrar enquanto estiver em combate.");
            return false;
        }
        if (player.isInOlympiadMode()) {
            player.sendMessage("Você não pode entrar durante uma partida da Olympiad.");
            return false;
        }
        if (player.getPvpFlag() != 0) {
            player.sendMessage("Você não pode entrar enquanto estiver em modo PvP.");
            return false;
        }
        if (player.isDead()) {
            player.sendMessage("Você não pode usar isto morto");
            return false;
        }
        if (player.isFakeDeath()) {
            player.sendMessage("Você não pode usar isto em FakeDeath");
            return false;
        }
        if (player.isFishing()) {
            player.sendMessage("Você não pode usar isto pescando");
            return false;
        }
        if (player.isInDuel()) {
            player.sendMessage("Você não pode usar isto em duelo");
            return false;
        }
        if (player.isInArena()) {
            player.sendMessage("Você não pode usar isto em arena");
            return false;
        }
        if (player.isInJail()) {
            player.sendMessage("Você não pode usar isto na Jail");
            return false;
        }
        if (player.isInStoreMode()) {
            player.sendMessage("Você não pode usar isto no momento");
            return false;
        }
        if (player.isInObserverMode()) {
            player.sendMessage("Você não pode usar isto no momento");
            return false;
        }
        if (player.getCast().isCastingNow()){
            player.sendMessage("Você não pode usar isto no momento");
            return false;
        }
        if (!allowRepeat.get()) {
            List<Long> entryTimes = _playerEntryTimes.getOrDefault(player.getObjectId(), new ArrayList<>());
            
            // Filtra para manter apenas as entradas de hoje
            List<Long> todayEntries = entryTimes.stream()
                .filter(this::isToday)
                .collect(Collectors.toList());

            if (todayEntries.size() > Config.MAX_DAILY_ENTRIES) { 
                showHtml(player, "322-daily-limit.htm");
                return false;
            }
            
            // Adiciona a entrada atual a lista antes de salvar
            todayEntries.add(System.currentTimeMillis());
            
            // Atualiza a lista de entradas
            _playerEntryTimes.put(player.getObjectId(), todayEntries);
        }
        return true;
    }
    
    private void proceedToInstance(List<Player> participants, boolean isSolo, L2Skill skillUsed) {
        Player player = participants.get(0);
        if (participants == null || participants.isEmpty()) {
            
            participants = Collections.singletonList(player);
            player.sendMessage("Erro ao coletar participantes.");
            //return;
        }
        

        // Se uma skill de canalização foi usada, verifica se ela foi completada por todos
        if (skillUsed != null) {
            for (Player member : participants) {
                if (member.getCast().getCurrentSkill().getId() != Config.CHANNELING_SKILL_ID) {
                    String message = "Sua entrada foi cancelada porque a preparação foi interrompida.";
                    if (!isSolo) {
                        message = "A preparação de " + member.getName() + " foi interrompida.";
                        participants.forEach(p -> {
                            p.abortAll(true);
                        return;
                        });
                    }
                    
                    final String finalMessage = message;
                    participants.forEach(p -> {
                        p.sendMessage(finalMessage);
                        if (!p.getCast().isCastingNow() || p.getCast().getCurrentSkill().getId() != Config.CHANNELING_SKILL_ID) p.abortAll(true);
                    });
                    return;
                }
            }
            // Se todos completaram, remove o buff de todos
            participants.forEach(member -> member.stopSkillEffects(skillUsed.getId()));
        }

        
        int dungeonId;

        if (isSolo) {
            dungeonId = getSoloDungeonIdForLevel(player.getStatus().getLevel());
            
            if (dungeonId == -1) {
                player.sendMessage("Seu nível não é compatível para entrar no Hall of the Abyss.");
                return;
            }
            //participants = Collections.singletonList(player);
        } else {
            int maxLevel = 0;
            for (Player member : participants) {
                if (member.getStatus().getLevel() > maxLevel) {
                    maxLevel = member.getStatus().getLevel();
                }
            }
            
            dungeonId = getPartyDungeonIdForLevel(maxLevel);
            if (dungeonId == -1) {
                player.getParty().broadcastToPartyMembers(player, new CreatureSay(0, SayType.PARTY, "Nível incompatível", "O nível do membro mais alto da party não é compatível para entrar no Labyrinth of the Abyss."));
                return;
            }
        }

        final DungeonTemplate template = DungeonData.getInstance().getDungeon(dungeonId);

        if (template == null) {
            LOGGER.warn("[" + getName() + "] Tentativa de entrar na dungeon com ID " + dungeonId + ", mas o template não foi encontrado no XML.");
            player.sendMessage("A configuração para esta dungeon não foi encontrada. Contate um administrador.");
            return;
        }

        try {
            long currentTime = System.currentTimeMillis();
            participants.forEach(p -> {
                List<Long> entryTimes = _playerEntryTimes.computeIfAbsent(p.getObjectId(), k -> new ArrayList<>());
                entryTimes.add(currentTime);
            });
            
            KamalokaDungeon dungeon = new KamalokaDungeon(template, participants);
            _dungeons.put(dungeon.getInstanceId(), dungeon);
            
        } catch (Exception e) {
            LOGGER.error("[" + getName() + "] Problemas ao criar a dungeon: ", e);
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



    private void handleLeaveInstance(Player player) {
        Dungeon dungeon = player.getDungeon();
        if (dungeon instanceof KamalokaDungeon) {
            // Converte para o nosso tipo de dungeon
            KamalokaDungeon kamaloka = (KamalokaDungeon) dungeon;
            // Chama o método de cancelamento que fará toda a limpeza e teleportará todos os jogadores para fora.
            kamaloka.cancelDungeon("A instância foi encerrada por um jogador.");
            player.setIsImmobilized(false);
            teleportPlayer(player, Config.TELEPORT_EXIT_LOC, "Voce saiu de Kamaloka.", 1);
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

    /*
    public void onKill(Creature attacker, Creature victim) {
        if (!(victim instanceof Npc npc) || npc.getNpcId() != int 2020) return;

        Player killer = attacker.getActingPlayer();
        if (killer == null || !(killer.getDungeon() instanceof KamalokaDungeon)) return;

        KamalokaDungeon dungeon = (KamalokaDungeon) killer.getDungeon();
        dungeon.onBossKill(killer);
    }*/

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
            final NpcHtmlMessage html = new NpcHtmlMessage(50020);
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
        private final String _dungeonName;
        
        public KamalokaDungeon(DungeonTemplate template, List<Player> players) {
            super(template, players); // Agora usa o template carregado do XML
            _dungeonName = template.name;
            
        }

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
            player.addItem(Config.REWARD_ITEM_ID, (int)Config.REWARD_ITEM_COUNT, true);
        }

        
        private void cleanupDungeon(boolean failed) {
            if (failed) {
                broadcastToDungeon("A incursão em " + _dungeonName + " falhou.");
                cancelDungeon();
            }

            getPlayers().forEach(p -> {
                if (p != null && p.isOnline()) {
                    KamalokaInstancia.getInstance().teleportPlayer(p, Config.TELEPORT_EXIT_LOC, "Voce foi retornado de " + _dungeonName + ".", 1);
                    p.setDungeon(null);
                    p.setInstanceMap(null, true);
                    p.setIsImmobilized(false);
                }
            });


            DungeonManager.getInstance().removeDungeon(this);
            KamalokaInstancia.getInstance().onDungeonFinish(this);

            cancelDungeon();
            
        }


        public int getInstanceId() {
            return _instanceId;
        }

        public void broadcastToDungeon(String message) {
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

        
    }
}