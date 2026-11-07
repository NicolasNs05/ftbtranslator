# Regras do projeto para agentes de IA (e para humanos)

Este arquivo é lido automaticamente por assistentes de código (Claude Code, Gemini/Antigravity, Codex e outros).
`CLAUDE.md` e `GEMINI.md` apenas apontam para cá: **edite somente este arquivo**.

Projeto: mod client-side **Universal Modpack & Quests Auto-Translator** (`ftbtranslator`) para NeoForge 1.21.1 / Java 21.
Arquitetura, frentes de tradução e decisões técnicas: [`ARCHITECTURE_AND_FEATURES.md`](ARCHITECTURE_AND_FEATURES.md).

---

## 1. Versionamento (obrigatório)

Usamos **Versionamento Semântico** no formato `MAIOR.MENOR.CORREÇÃO` (ex.: `0.4.2`).

| Parte | Quando aumentar | Exemplo |
|---|---|---|
| **MAIOR** | Mudança incompatível: configuração antiga deixa de funcionar, formato do cache ou do bundle `.zip` muda, comando removido | `1.4.2` → `2.0.0` |
| **MENOR** | Recurso novo compatível (nova aba, novo provedor, nova opção) | `1.4.2` → `1.5.0` |
| **CORREÇÃO** | Só correção de bug, sem recurso novo | `1.4.2` → `1.4.3` |

Ao subir uma parte, as partes à direita voltam para zero (`0.3.7` → `0.4.0`).

### Fases
- **`0.x.y` = em desenvolvimento**, ainda não lançado ao público. Nessa fase, mudanças incompatíveis sobem o **MENOR** (`0.3.0` → `0.4.0`), não o MAIOR.
- **`1.0.0` = primeiro lançamento público** (CurseForge / Modrinth). Só a equipe decide quando.
- **Testes antes de uma versão:** sufixo `-beta.N` ou `-rc.N` (ex.: `v0.2.0-beta.1`).

### Onde a versão fica
- **`gradle.properties` → `mod_version`** guarda a versão **em desenvolvimento** (a próxima a ser lançada).
  Logo depois de lançar uma versão, o próximo PR deve subir `mod_version` para a próxima versão planejada
  (ex.: após `v0.1.0`, mudar para `0.2.0`).
- **Builds comuns** (push/PR) geram `.jar` como `<mod_version>-build.<n>`: são só para teste interno.
- **A versão oficial é definida pela tag Git.** O workflow compila com `-Pmod_version=<tag sem o v>`.

### Como lançar uma versão
1. Garanta que tudo o que entra na versão já está **mergeado no `main`** e que o build do `main` está verde.
2. Crie e envie a tag **a partir do `main` atualizado**:
   ```bash
   git checkout main
   git pull origin main
   git tag v0.2.0
   git push origin v0.2.0
   ```
3. O GitHub Actions (`.github/workflows/build.yml`) compila, roda os testes e cria a **Release** com o `.jar` anexado
   e as notas geradas a partir dos títulos dos PRs.
   - Tags `v0.*` ou com sufixo (`-beta.1`, `-rc.1`) viram **Pre-release** automaticamente.
4. Nunca reutilize nem mova uma tag já publicada; se algo saiu errado, lance uma **CORREÇÃO** (`v0.2.1`).

### Títulos de PR
As notas da Release são montadas com os títulos dos PRs, então escreva títulos claros em português,
começando pelo tipo: `feat:` (recurso), `fix:` (correção), `perf:`, `docs:`, `ci:`, `refactor:`.

---

## 2. Regras de código

- **Isolamento do core:** nada em `src/core/java` (`com.ftbtranslator.core.*`) pode importar `net.minecraft.*`,
  `com.mojang.*` ou `net.neoforged.*`. O core usa só Java 21 + Gson. A camada NeoForge fica em `src/main/java`.
- **Testes:** `./gradlew coreTest` (Windows: `.\gradlew.bat coreTest`) roda a suíte do core sem o jogo.
  Todo recurso ou correção no core deve vir com teste em `src/coreTest`.
- **Textos para o jogador** (interface, tooltips, logs visíveis) em **português do Brasil**.

## 3. Fluxo de Git

- Trabalhe em branch e abra PR para o `main`; o CI precisa estar verde antes do merge.
- **Não envie commits para a branch de um PR depois que ele foi mergeado**: eles ficam fora do `main`.
  Para continuar, atualize a branch a partir do `main` e abra um PR novo.
