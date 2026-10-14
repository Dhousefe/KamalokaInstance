package mods.dhousefe.kamaloka;

import ext.mods.commons.logging.CLogger;
import ext.mods.gameserver.model.location.Location;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
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

    // --- Cache das Configuracoes Gerais ---
    public static int CHANNELING_SKILL_ID;
    public static Location TELEPORT_EXIT_LOC;
    public static int REWARD_ITEM_ID;
    public static long REWARD_ITEM_COUNT;
    public static int MAX_DAILY_ENTRIES;

    // --- Cobranca de Entrada por Item Custom ---
    public static boolean SOLO_ENTRY_FEE_ENABLED;
    public static int SOLO_ENTRY_FEE_ITEM_ID;
    public static int SOLO_ENTRY_FEE_ITEM_COUNT;

    public static boolean PARTY_ENTRY_FEE_ENABLED;
    public static int PARTY_ENTRY_FEE_ITEM_ID;
    public static int PARTY_ENTRY_FEE_ITEM_COUNT;

    /**
     * Carrega as configuracoes do arquivo kamaloka.properties.
     * Pode ser chamado a qualquer momento para recarregar as configuracoes em runtime.
     */
    public static void load() {
        Properties props = new Properties();
        Path path = Path.of("config/kamaloka.properties");
        if (!Files.exists(path)) {
            Path alternative = Path.of("config/mods/kamaloka.properties");
            if (Files.exists(alternative)) {
                path = alternative;
            }
        }

        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            props.load(fis);
        } catch (IOException e) {
            LOGGER.warn("Falha ao carregar kamaloka.properties de " + path + ". Usando valores padrao.", e);
        }

        CHANNELING_SKILL_ID = getInt(props, "ChannelingSkillId", 2013);
        TELEPORT_EXIT_LOC = getLocation(props, "TeleportExitLocation", new Location(83425, 148585, -3406));
        REWARD_ITEM_ID = getInt(props, "RewardItemId", 4037);
        REWARD_ITEM_COUNT = getLong(props, "RewardItemCount", 15L);
        MAX_DAILY_ENTRIES = getInt(props, "MaxDailyEntries", 2);

        // Configuracoes de Cobranca de Entrada Solo
        SOLO_ENTRY_FEE_ENABLED = getBoolean(props, "SoloEntryFeeEnabled", false);
        SOLO_ENTRY_FEE_ITEM_ID = getInt(props, "SoloEntryFeeItemId", 57);
        SOLO_ENTRY_FEE_ITEM_COUNT = getInt(props, "SoloEntryFeeItemCount", 1000000);

        // Configuracoes de Cobranca de Entrada Party
        PARTY_ENTRY_FEE_ENABLED = getBoolean(props, "PartyEntryFeeEnabled", false);
        PARTY_ENTRY_FEE_ITEM_ID = getInt(props, "PartyEntryFeeItemId", 57);
        PARTY_ENTRY_FEE_ITEM_COUNT = getInt(props, "PartyEntryFeeItemCount", 1000000);

        LOGGER.info("[KamalokaInstancia] Configuracoes de Kamaloka carregadas com sucesso de " + path);
    }

    private static String getProperty(Properties props, String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
        String val = getProperty(props, key, null);
        if (val == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(val.trim());
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(props, key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de numero invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
            return defaultValue;
        }
    }

    private static long getLong(Properties props, String key, long defaultValue) {
        try {
            return Long.parseLong(getProperty(props, key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Formato de numero (long) invalido para a chave: " + key + ". Usando valor padrao: " + defaultValue);
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
            LOGGER.warn("Formato de localizacao invalido para a chave: " + key + ". O formato deve ser X,Y,Z", e);
        }
        return defaultValue;
    }
}
