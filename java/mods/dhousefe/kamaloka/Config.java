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
    private static final Properties _props = new Properties();

    // --- Cache das Configuracoes ---
    public static int CHANNELING_SKILL_ID;
    public static Location TELEPORT_EXIT_LOC;
    public static int REWARD_ITEM_ID;
    public static int REWARD_ITEM_COUNT;
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

    /**
     * Carrega as configuracoes do arquivo kamaloka.properties.
     * Se o arquivo nao existir ou ocorrer um erro, valores padrao serao usados.
     */
    public static void load() {
        Path path = Path.of("config/kamaloka.properties");
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            _props.load(fis);
        } catch (IOException e) {
            LOGGER.error("Falha ao carregar kamaloka.properties. Usando valores padrao.", e);
        }

        // Carrega os valores do arquivo para as variaveis estaticas
        CHANNELING_SKILL_ID = getInt("ChannelingSkillId", 2013);
        TELEPORT_EXIT_LOC = getLocation("TeleportExitLocation", new Location(83425, 148585, -3406));
        REWARD_ITEM_ID = getInt("RewardItemId", 4037);
        REWARD_ITEM_COUNT = getInt("RewardItemCount", 15);

        // Monstros Solo
        SOLO_MONSTER_LEVEL_BONUS = getInt("SoloMonsterLevelBonus", 5);
        SOLO_MONSTER_HP_MULTIPLIER = getDouble("SoloMonsterHpMultiplier", 1.4);
        SOLO_MONSTER_PDEF_MULTIPLIER = getDouble("SoloMonsterPdefMultiplier", 1 / 1.1);
        SOLO_MONSTER_MIN_HP_MULTIPLIER = getDouble("SoloMonsterMinHpMultiplier", 3.0);
        SOLO_MONSTER_MDEF_MULTIPLIER = getDouble("SoloMonsterMdefMultiplier", 1.1);

        // Monstros Party
        PARTY_MONSTER_LEVEL_BONUS = getInt("PartyMonsterLevelBonus", 7);
        PARTY_MONSTER_HP_MULTIPLIER = getDouble("PartyMonsterHpMultiplier", 1.9);
        PARTY_MONSTER_PDEF_MULTIPLIER = getDouble("PartyMonsterPdefMultiplier", 1.2);
        PARTY_MONSTER_MDEF_MULTIPLIER = getDouble("PartyMonsterMdefMultiplier", 1.2);
        PARTY_MONSTER_MIN_HP_MULTIPLIER = getDouble("PartyMonsterMinHpMultiplier", 8.0);

        // Raid Solo
        SOLO_RAID_PATK_MULTIPLIER = getDouble("SoloRaidPatkMultiplier", 1.0 / 8.0);
        SOLO_RAID_MATK_MULTIPLIER = getDouble("SoloRaidMatkMultiplier", 1.0 / 8.0);
        SOLO_RAID_MDEF_MULTIPLIER = getDouble("SoloRaidMdefMultiplier", 1.0 / 5.0);
        SOLO_RAID_PDEF_MULTIPLIER = getDouble("SoloRaidPdefMultiplier", 1.0 / 2.0);

        // Raid Party
        PARTY_RAID_PATK_MULTIPLIER = getDouble("PartyRaidPatkMultiplier", 1.0 / 4.0);
        PARTY_RAID_MATK_MULTIPLIER = getDouble("PartyRaidMatkMultiplier", 1.0 / 4.0);
        PARTY_RAID_MDEF_MULTIPLIER = getDouble("PartyRaidMdefMultiplier", 1.0 / 2.0);
        
        LOGGER.info("Configuracoes de Kamaloka carregadas com sucesso.");
    }

    private static String getProperty(String key, String defaultValue) {
        return _props.getProperty(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de numero invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }

    private static double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de double invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }

    private static Location getLocation(String key, Location defaultValue) {
        String value = getProperty(key, null);
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