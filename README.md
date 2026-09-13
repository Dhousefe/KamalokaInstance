# ⚔️ Kamaloka Instance - L2J & BrProject-2026

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg?style=flat-square&logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![L2J Extension](https://img.shields.io/badge/L2J-Extension%20Core-blue.svg?style=flat-square)](https://github.com/)
[![BrProject-2026 SPI](https://img.shields.io/badge/BrProject--2026-Extension%20SPI%20Ready-green.svg?style=flat-square)](https://github.com/)
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads-blueviolet.svg?style=flat-square)](https://openjdk.org/jeps/444)
[![JMH Benchmarked](https://img.shields.io/badge/JMH%20Throughput-+189x-brightgreen.svg?style=flat-square)](https://github.com/openjdk/jmh)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-lightgrey.svg?style=flat-square)](LICENSE)

> Módulo corporativo de instâncias de masmorra (**Rim Kamaloka**) para servidores Lineage II, combinando compatibilidade híbrida entre o ecossistema clássico L2J e a nova arquitetura modular **BrProject-2026 SPI**, operando com concorrência escalável em **Virtual Threads** e zero gargalos de GC.

---

## 📑 Índice
- [Visão Geral](#-visão-geral)
- [Destaques de Engenharia](#-destaques-de-engenharia)
- [Arquitetura do Sistema](#-arquitetura-do-sistema)
- [Ciclo de Vida & Fluxo de Execução](#-ciclo-de-vida--fluxo-de-execução)
- [Matriz de Dungeons por Nível](#-matriz-de-dungeons-por-nível)
- [Benchmark de Performance (JMH 1.37)](#-benchmark-de-performance-jmh-137)
- [Bypasses & Comandos](#-bypasses--comandos)
- [Configurações (`kamaloka.properties`)](#-configurações-kamalokaproperties)
- [Instalação & Implantação Sem Regressão](#-instalação--implantação-sem-regressão)
- [Licença](#-licença)

---

## 🌟 Visão Geral

O **Kamaloka Instance** implementa o sistema de desafios de masmorra instanciada para jogadores individuais (*Solo*) e grupos (*Party*). Os jogadores enfrentam ondas de monstros balanceados dinamicamente com base em seu nível e encontram um Raid Boss desafiador no final para receber recompensas exclusivas do servidor.

### Principais Benefícios:
- **Compatibilidade Dual:** Roda nativamente no L2J tradicional (`L2JExtension`) ou no motor modular do BrProject-2026 (`br.project.spi.Extension`).
- **Resiliência em Concorrência:** Finalização idempotente com transições atômicas via CAS (`AtomicBoolean`) e fallback de saída de emergência para jogadores desconectados.
- **Desempenho Extremo:** Validações de limites temporais operando em aritmética de *Epoch Day* direto em registradores de CPU, sem geração de lixo no Heap.

---

## ⚡ Destaques de Engenharia

| Componente | Implementação Tradicional | Engenharia KamalokaInstance | Impacto |
| :--- | :--- | :--- | :--- |
| **I/O & Concorrência** | Threads pesadas do SO ou thread de rede bloqueada | `Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())` | Desacoplamento total da thread de rede do servidor |
| **Cálculo de Data/Hoje** | `new SimpleDateFormat("yyyyMMdd").format(new Date())` | `(timestamp + OFFSET) / MILLIS_PER_DAY` | **189x mais rápido**, zero alocação de objetos no Heap |
| **Limpeza de Instância** | Múltiplas chamadas recursivas a `cancelDungeon()` | Controle atômico com `_isCleaningUp.compareAndSet(false, true)` | Eliminação de *double-free*, corridas de teleporte e crashes |
| **Resgate de Jogadores** | Bloqueio do jogador com mensagem de erro | Detecção de `player.getInstanceMap().getId() > 0` | Resgate instantâneo mesmo após desconexão ou perda do vínculo de dungeon |
| **Acoplamento SPI** | Reflexão em runtime ou acoplamento forte | `java.util.ServiceLoader` + `META-INF/services` | Pluga sem quebrar builds antigos e sem alterar o core |

---

## 🏗️ Arquitetura do Sistema

O diagrama abaixo ilustra o relacionamento entre o motor do servidor, as camadas de extensão SPI e os módulos internos do Kamaloka:

```mermaid
classDiagram
    direction TB

    namespace L2J_Core {
        class L2JExtension {
            <<interface>>
            +onLoad() void
            +onDisable() void
            +getName() String
        }
        class OnBypassCommandListener {
            <<interface>>
            +onBypass(Player player, String command) boolean
        }
        class Dungeon {
            <<external>>
            +getPlayers() List~Player~
            +cancelDungeon() void
        }
    }

    namespace BrProject_SPI {
        class Extension {
            <<interface>>
            +id() String
            +version() String
            +onLoad(ExtensionContext context) void
            +onEnable(ExtensionContext context) void
            +onDisable(ExtensionContext context) void
        }
    }

    namespace Kamaloka_Engine {
        class KamalokaInstancia {
            -Map~Integer, KamalokaDungeon~ _dungeons
            -Map~Integer, List~Long~~ _playerEntryTimes
            -ScheduledExecutorService _executor
            +getInstance() KamalokaInstancia
            +onBypass(Player player, String command) boolean
            -handleEnterInstance(Player player, boolean isSolo) void
            -handleLeaveInstance(Player player) void
        }
        class KamalokaDungeon {
            -int _instanceId
            -String _dungeonName
            -AtomicBoolean _isCleaningUp
            +onBossKill(Player killer) void
            +checkPartyWipe() void
            +cleanupDungeon(boolean failed) void
        }
        class Config {
            +CHANNELING_SKILL_ID : int
            +TELEPORT_EXIT_LOC : Location
            +MAX_DAILY_ENTRIES : int
            +load() void
        }
        class OriginalStats {
            -byte level
            -double baseHpMax
            -double basePDef
            -double baseMDef
        }
    }

    L2JExtension <|.. KamalokaInstancia : Implementa
    Extension <|.. KamalokaInstancia : Implementa (Dual SPI)
    OnBypassCommandListener <|.. KamalokaInstancia : Escuta bypasses
    Dungeon <|-- KamalokaDungeon : Herda
    KamalokaInstancia *-- KamalokaDungeon : Gerencia ativas
    KamalokaInstancia *-- OriginalStats : Cache imutável
    KamalokaInstancia ..> Config : Regras
```

---

## 🔄 Ciclo de Vida & Fluxo de Execução

Do acionamento da interface gráfica (HTML/NPC) à entrega de premiações e retorno à cidade:

```mermaid
sequenceDiagram
    autonumber
    actor Player as 🧙‍♂️ Jogador / Party
    participant NPC as 🏛️ NPC Guardião
    participant Mod as ⚙️ KamalokaInstancia
    participant Dung as 🏰 KamalokaDungeon
    participant Map as 🗺️ InstanceManager

    Player->>NPC: Clica em "Entrar" (kamaloka_enter / solo)
    NPC->>Mod: onBypass(player, command)
    activate Mod
    Mod->>Mod: checkPlayerRestrictions() [Combate, Eventos, Limite Diário]
    
    alt Restrição detectada
        Mod-->>Player: Mensagem de erro / Alerta
    else Validações OK
        Mod->>Player: Inicia canalização da skill (Config.CHANNELING_SKILL_ID)
        Note over Mod,Player: Execução assíncrona via Virtual Threads
        Mod->>Dung: new KamalokaDungeon(template, participants)
        activate Dung
        Dung->>Map: Aloca instância única de mapa
        Dung->>Player: Teleporta para dentro da Dungeon
        deactivate Mod
        
        loop Batalha na Masmorra
            Player->>Dung: Derrota monstros & Raid Boss
            alt Party Wipe (Todos morreram)
                Dung->>Dung: cleanupDungeon(failed = true)
            else Raid Boss Morto
                Dung->>Player: Entrega premiações (Config.REWARD_ITEM_ID)
                Dung->>Dung: cleanupDungeon(failed = false)
            end
        end
        
        Note over Dung: Execução atômica (AtomicBoolean CAS)
        Dung->>Map: Remove instância do mundo (Instance 0)
        Dung->>Player: Teleporta de volta para Config.TELEPORT_EXIT_LOC
        deactivate Dung
    end
```

---

## 🎯 Matriz de Dungeons por Nível

O sistema seleciona a masmorra adequada calculando a faixa de nível do jogador (ou do jogador de maior nível na party):

| Faixa de Nível | Tipo | ID da Dungeon | Nome do Template | Nível Base |
| :---: | :---: | :---: | :---: | :---: |
| **20 – 24** | Solo | `10` | Kamaloka Solo (20-24) | 20 |
| **25 – 29** | Solo | `11` | Kamaloka Solo (25-29) | 25 |
| **30 – 34** | Solo | `12` | Kamaloka Solo (30-34) | 30 |
| **35 – 39** | Solo | `13` | Kamaloka Solo (35-39) | 35 |
| **40 – 44** | Solo | `14` | Kamaloka Solo (40-44) | 40 |
| **45 – 49** | Solo | `15` | Kamaloka Solo (45-49) | 45 |
| **50 – 54** | Solo | `16` | Kamaloka Solo (50-54) | 50 |
| **55 – 59** | Solo | `17` | Kamaloka Solo (55-59) | 55 |
| **60 – 64** | Solo | `18` | Kamaloka Solo (60-64) | 60 |
| **65 – 69** | Solo | `19` | Kamaloka Solo (65-69) | 65 |
| **70 – 74** | Solo | `20` | Kamaloka Solo (70-74) | 70 |
| **75 – 80+** | Solo | `21` | Kamaloka Solo (75-80) | 75 |
| **20 – 24** | Party | `22` | Kamaloka Party (20-24) | 20 |
| **25 – 29** | Party | `23` | Kamaloka Party (25-29) | 25 |
| **...** | Party | `...` | ... | ... |
| **75 – 80+** | Party | `33` | Kamaloka Party (75-80) | 75 |

---

## 📊 Benchmark de Performance (JMH 1.37)

Para certificar que o mod sustenta alto tráfego com centenas de jogadores abrindo janelas de diálogo simultaneamente, foi executado microbenchmark formal sob o **Java Microbenchmark Harness (JMH)**:

```text
Benchmark                               Mode  Cnt          Score         Error  Units
KamalokaDateBenchmark.baselineLegacy   thrpt    5    2495819.336 ±   84562.115  ops/s
KamalokaDateBenchmark.optimizedEpoch   thrpt    5  473180424.908 ± 4578129.431  ops/s
```

```mermaid
pie title Comparativo de Throughput (ops/s)
    "Legado (SimpleDateFormat) - 2.49M ops/s" : 2.5
    "Otimizado (Epoch Math) - 473.18M ops/s (+189x)" : 473.2
```

> [!TIP]
> **Ganho de 189x em throughput:** Ao eliminar alocações desnecessárias de `SimpleDateFormat` e instâncias temporárias de `Date`, a pressão no Garbage Collector em cenários de pico caiu para zero no fluxo de validação de bypass.

---

## 🎮 Bypasses & Comandos

Os diálogos HTML do NPC interagem com a extensão através do prefixo `kamaloka_`:

| Ação Bypass | Descrição | Restrições Verificadas |
| :--- | :--- | :--- |
| `bypass -h kamaloka_enter` | Entrada em grupo (Party) | Nível do grupo, status de combate, ausência de karma, limites diários. |
| `bypass -h kamaloka_enter_solo` | Entrada individual (Solo) | Nível do jogador, status de combate, ausência de karma, limites diários. |
| `bypass -h kamaloka_leave` | Saída imediata da masmorra | Finaliza a instância, limpa timers concorrentes e teleporta para a cidade com resgate seguro. |
| `bypass -h kamaloka_toggle_repeat` | Alterna modo de testes diários | Exclusivo para administradores / GMs (`player.isGM()`). |

---

## ⚙️ Configurações (`kamaloka.properties`)

O arquivo é gerado automaticamente na primeira inicialização em `config/kamaloka.properties`:

```properties
# ID da habilidade de canalização antes do teleporte
ChannelingSkillId = 2013

# Coordenadas de retorno ao sair da Kamaloka (X, Y, Z)
TeleportExitLocation = 83425,148585,-3406

# Item de premiação e quantidade ao derrotar o Raid Boss
RewardItemId = 4037
RewardItemCount = 15

# Quantidade máxima de entradas diárias permitidas por jogador
MaxDailyEntries = 2

# Multiplicadores de atributos para Monstros e Raids Solo/Party
SoloMonsterHpMultiplier = 2.2
PartyMonsterHpMultiplier = 4.9
```

---

## 🚀 Instalação & Implantação Sem Regressão

> [!IMPORTANT]
> A extensão foi construída com suporte dual. Você pode utilizá-la em servidores L2J tradicionais ou em forks atualizados do **BrProject-2026** sem necessidade de recompilar o núcleo do servidor.

### 1. Compilação & Empacotamento
Certifique-se de possuir o JDK 21 instalado e a biblioteca `libs/server.jar` presente no projeto:

```bash
# Compilação dos arquivos fonte
javac -cp "libs/server.jar" -d bin java/br/project/spi/*.java java/mods/dhousefe/kamaloka/*.java

# Empacotamento do JAR de extensão
jar --create --file dist/Kamaloka.ext.jar -C bin .
```

### 2. Deploy no Servidor
1. Copie o arquivo gerado `dist/Kamaloka.ext.jar` para o diretório de extensões do seu servidor:
   ```bash
   cp dist/Kamaloka.ext.jar /caminho/do/servidor/libs/extensions/
   ```
2. Inicie o servidor. Na primeira execução, o KamalokaInstance criará automaticamente os arquivos padrão:
   - `config/kamaloka.properties`
   - `data/custom/mods/Kamaloka_Dungeon.xml`
   - `data/xml/npcs/kamaloka.xml`
   - `data/locale/en_US/html/kamaloka/*.htm`

---

## 📄 Licença

Este projeto é distribuído sob os termos da licença [GNU General Public License v3.0](LICENSE).
Sinta-se livre para contribuir, reportar issues ou enviar pull requests.
