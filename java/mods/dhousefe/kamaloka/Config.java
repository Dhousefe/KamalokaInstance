package mods.dhousefe.kamaloka;

import ext.mods.commons.logging.CLogger;
import ext.mods.gameserver.model.location.Location;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Classe responsavel por carregar e fornecer acesso as configuracoes
 * do arquivo kamaloka.properties.
 *
 * @author Dhousefe
 */
public final class Config {
    private static final CLogger LOGGER = new CLogger(Config.class.getName());

    // --- Cache das Configuracoes ---
    public static int CHANNELING_SKILL_ID;
    public static Location TELEPORT_EXIT_LOC;
    public static int REWARD_ITEM_ID;
    public static long REWARD_ITEM_COUNT; // Alterado para long para consistência com addItem
    public static int SOLO_MONSTER_LEVEL_BONUS;
    public static double SOLO_MONSTER_HP_MULTIPLIER;
    public static double SOLO_MONSTER_PDEF_MULTIPLIER;
    public static double SOLO_MONSTER_MDEF_MULTIPLIER;
    public static double SOLO_MONSTER_MIN_HP_MULTIPLIER;
    public static int PARTY_MONSTER_LEVEL_BONUS;
    public static double PARTY_MONSTER_HP_MULTIPLIER;
    public static double PARTY_MONSTER_PDEF_MULTIPLIER;
    public static double PARTY_MONSTER_MDEF_MULTIPLIER;
    public static double PARTY_MONSTER_MIN_HP_MULTIPLIER;
    public static double SOLO_RAID_PATK_MULTIPLIER;
    public static double SOLO_RAID_MATK_MULTIPLIER;
    public static double SOLO_RAID_MDEF_MULTIPLIER;
    public static double SOLO_RAID_PDEF_MULTIPLIER;
    public static double PARTY_RAID_PATK_MULTIPLIER;
    public static double PARTY_RAID_MATK_MULTIPLIER;
    public static double PARTY_RAID_MDEF_MULTIPLIER;
    public static double PARTY_RAID_PDEF_MULTIPLIER; 
    public static int MAX_DAILY_ENTRIES; 

    /**
     * Carrega as configuracoes do arquivo kamaloka.properties.
     * Este método pode ser chamado a qualquer momento para recarregar as configurações.
     */
    public static void load() {
        Properties props = new Properties();
        Path path = Path.of("config/kamaloka.properties");
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            props.load(fis);
        } catch (IOException e) {
            LOGGER.error("Falha ao carregar kamaloka.properties. Usando valores em cache ou padrao.", e);
            // Não retorna, permite que o código continue com os valores antigos ou padrão.
        }

        // Carrega os valores do arquivo para as variaveis estaticas
        CHANNELING_SKILL_ID = getInt(props, "ChannelingSkillId", 2013);
        TELEPORT_EXIT_LOC = getLocation(props, "TeleportExitLocation", new Location(83425, 148585, -3406));
        REWARD_ITEM_ID = getInt(props, "RewardItemId", 4037);
        REWARD_ITEM_COUNT = getLong(props, "RewardItemCount", 15);

        // Monstros Solo
        SOLO_MONSTER_LEVEL_BONUS = getInt(props, "SoloMonsterLevelBonus", 5);
        SOLO_MONSTER_HP_MULTIPLIER = getDouble(props, "SoloMonsterHpMultiplier", 2.2);
        SOLO_MONSTER_PDEF_MULTIPLIER = getDouble(props, "SoloMonsterPdefMultiplier", 1 / 1.8);
        SOLO_MONSTER_MIN_HP_MULTIPLIER = getDouble(props, "SoloMonsterMinHpMultiplier", 2.0);
        SOLO_MONSTER_MDEF_MULTIPLIER = getDouble(props, "SoloMonsterMdefMultiplier", 1.8);

        // Monstros Party
        PARTY_MONSTER_LEVEL_BONUS = getInt(props, "PartyMonsterLevelBonus", 1);
        PARTY_MONSTER_HP_MULTIPLIER = getDouble(props, "PartyMonsterHpMultiplier", 4.9);
        PARTY_MONSTER_PDEF_MULTIPLIER = getDouble(props, "PartyMonsterPdefMultiplier", 1.2);
        PARTY_MONSTER_MDEF_MULTIPLIER = getDouble(props, "PartyMonsterMdefMultiplier", 1.2);
        PARTY_MONSTER_MIN_HP_MULTIPLIER = getDouble(props, "PartyMonsterMinHpMultiplier", 8.0);

        // Raid Solo
        SOLO_RAID_PATK_MULTIPLIER = getDouble(props, "SoloRaidPatkMultiplier", 1.0 / 8.0);
        SOLO_RAID_MATK_MULTIPLIER = getDouble(props, "SoloRaidMatkMultiplier", 1.0 / 8.0);
        SOLO_RAID_MDEF_MULTIPLIER = getDouble(props, "SoloRaidMdefMultiplier", 1.0 / 5.0);
        SOLO_RAID_PDEF_MULTIPLIER = getDouble(props, "SoloRaidPdefMultiplier", 1.0 / 2.0);

        // Raid Party
        PARTY_RAID_PATK_MULTIPLIER = getDouble(props, "PartyRaidPatkMultiplier", 1.0 / 4.0);
        PARTY_RAID_MATK_MULTIPLIER = getDouble(props, "PartyRaidMatkMultiplier", 1.0 / 4.0);
        PARTY_RAID_MDEF_MULTIPLIER = getDouble(props, "PartyRaidMdefMultiplier", 1.0 / 2.0);
        PARTY_RAID_PDEF_MULTIPLIER = getDouble(props, "PartyRaidPdefMultiplier", 1.0 / 1.5); 
        
        MAX_DAILY_ENTRIES = getInt(props, "MaxDailyEntries", 2);

        LOGGER.info("[KamalokaInstancia] Configuracoes de Kamaloka carregadas recarregadas com sucesso.");
    }

    private static String getProperty(Properties props, String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(props, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de numero invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }
    
    private static long getLong(Properties props, String key, long defaultValue) {
        try {
            return Long.parseLong(getProperty(props, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de numero (long) invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }

    private static double getDouble(Properties props, String key, double defaultValue) {
        try {
            return Double.parseDouble(getProperty(props, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de double invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }

    private static Location getLocation(Properties props, String key, Location defaultValue) {
        String value = getProperty(props, key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            String[] parts = value.split(",");
            if (parts.length == 3) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                return new Location(x, y, z);
            }
        } catch (Exception e) {
            LOGGER.warn("Formato de localizacao invalido para a chave: " + key + ". Usando valor padrao. O formato deve ser X,Y,Z", e);
        }
        return defaultValue;
    }
}
