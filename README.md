# Bedrock Creator Studio — Etapas 1 a 4: núcleo (`:core`)

Biblioteca Kotlin/JVM pura (só `java.nio` e `java.util.zip`; funciona no Android 8.0+ / API 26+).

| Pacote | Função |
|---|---|
| `json` | Parser/escritor JSON com linha e coluna nos erros; aceita comentários e BOM; preserva ordem das chaves, texto dos números e campos desconhecidos |
| `storage` | Gravação atômica (temp + fsync + move), limpeza de temporários, `StorageException` |
| `archive` | `SafeZip`: extração com proteção contra zip-slip, duplicatas e bombas; criação atômica |
| `manifest` | UUIDs, `ManifestBuilder` (format_version 2), `ManifestReader` |
| `diagnostics` | Verificação de pacotes/projetos separada em sintaxe, estrutura, compatibilidade e "precisa de teste no jogo" |
| `project` | `ProjectStore`/`Project`: criar, abrir, listar, duplicar, excluir, backup, importação, rascunhos e recuperação |
| `image` | PNG próprio (lê 1–16 bits, paleta, tRNS; grava RGBA), `Bitmap`, camadas, `Editor` (pincel, borracha, linha, retângulo, elipse, balde com tolerância, varinha, seleção, copiar/colar/mover, simetria, desfazer/refazer, transformações), paleta, arquivo de camadas `.bcsimg` |
| `skin` | Layout 64x64 (Classic/Slim, base/externa), máscaras de seleção, geometria para a prévia 3D, conversão 64x32→64x64, validação de tamanho, gerador de pacote de skins |
| `addon` | Catálogo de componentes (entidade/bloco/item) com validação de tipo e intervalo; definições de entidade, entidade de cliente, render controller, bloco (estados e permutações), item, receitas (shaped/shapeless/furnace), loot tables, spawn rules; mesclagem de atlas e `.lang`; lint de JavaScript e `.mcfunction`; `AddonWriter` (grava tudo-ou-nada num projeto) e `AddonEdit` (edita componentes no arquivo real) |
| `geometry` | Modelo de geometria (ossos, cubos, UV de caixa e por face) com leitura/escrita que preserva campos desconhecidos e outras geometrias do arquivo; formato antigo (1.8) lido e convertido; `GeometryEditor` com desfazer/refazer (ossos: adicionar/remover/renomear/mudar pai/mover/pivô/rotação; cubos: adicionar/mover/redimensionar/duplicar/trocar de osso; espelhar subárvore); UV (desdobramento de caixa, sobreposição, organização automática com remapeamento da textura, máscara para o editor de texturas); pose, picking por raio e `OrbitCamera`; `ModelStore` (lê/grava os arquivos reais do projeto, liga animações à entidade, renomeia osso em geometria + animações) |
| `animation` | Molang (subconjunto: aritmética, lógica, ternário, `??`, variáveis, `return`, `math.*` em graus, `query.*` informado pela interface; o que não é suportado é sinalizado, nunca adivinhado); animações (`loop`, `animation_length`, canais rotation/position/scale, quadros-chave com pre/post, `catmullrom`, valores Molang); `AnimSampler`, `AnimationPlayer` (tocar/pausar/reiniciar/velocidade), `AnimationEditor` (quadros-chave, tempo, inverter, copiar espelhado, desfazer/refazer), `Timeline`; controladores de animação (estados, transições, mistura) com editor |
| `render` | `SoftRenderer`: prévia 3D por software (CPU), usa a textura e os UVs reais, z-buffer, recorte alfa, destaque de cubo selecionado |
| `pack` | Exportação `.mcpack`/`.mcaddon`, pré-validação, inspeção e verificação final |

## Compilar e testar
Requer JDK 17+ e Gradle (ou Android Studio). Sem wrapper incluído:

    gradle wrapper      # uma vez
    ./gradlew :core:test

## Como foi verificado (etapa 4)
Maven Central ficou bloqueado no ambiente de criação, então o build Gradle **não foi executado**. O código foi compilado com o compilador Kotlin 2.0.21 que vem dentro do Gradle e os testes rodaram com JUnit 4 (156 testes, todos passando), usando um adaptador mínimo para `kotlin.test`. Isso corrigiu dois erros reais das etapas anteriores (comentários `/*` aninhados em KDoc). Com Gradle e internet, `./gradlew :core:test` deve dar o mesmo resultado, mas isso não foi conferido.

## Limites conhecidos
- **Nada foi testado dentro do Minecraft.** A prévia é aproximada.
- Escrito de memória, a conferir com a documentação oficial e no jogo: `format_version` padrão (geometria 1.12.0, animação 1.8.0, controlador 1.10.0); posição das faces no desdobramento de caixa (`BoxUv`); sinais da convenção de rotação/posição (`PoseConvention`); pivô padrão de cubo com `rotation` sem `pivot` (assumido 0,0,0 na prévia); interpolação `catmullrom`; arredondamento de tamanhos fracionários no UV de caixa.
- Geometria: não há `poly_mesh`, `texture_meshes`, `locators` e `binding` na interface (são preservados no arquivo, mas não editados nem desenhados). Geometria com erro estrutural não abre no editor visual (o arquivo não é alterado). Formato antigo: herança (`geometry.a:geometry.b`) não é convertida.
- Animações: eventos (`timeline`, `sound_effects`, `particle_effects`), `anim_time_update`, `blend_weight` e `lerp_mode` além de linear/catmullrom são preservados no arquivo mas ignorados na prévia. Molang fora do subconjunto (arrays, `loop`, structs, `->`, funções de query) vale 0 na prévia e é listado em `MolangContext.unresolved`.
- Reescrever JSON pelo editor remove comentários do arquivo original, e números como `1e2` viram `100`.
- Ainda não existem: interface Compose (a lógica das etapas 1–4 está pronta para ser ligada a ela), partículas, sons, editor de blocos com geometria própria, perfis de compatibilidade por versão, histórico de alterações de arquivos, backups automáticos.
- O renderizador por software não faz recorte de plano próximo (faces atrás/perto demais da câmera são puladas) e não tem antialiasing; serve de prévia e para testes, não substitui um renderizador OpenGL em tempo real.

## Etapa 5 (parcial): módulo `:app` (Android + Compose)
Esqueleto real, **NÃO COMPILADO** — o ambiente de criação não tem Android SDK nem acesso ao Maven/Google. Tem: lista de projetos (criar, importar .mcpack/.mcaddon/.zip, excluir com confirmação), tela do projeto (arquivos, diagnóstico, exportar .mcaddon para o local escolhido ou compartilhar), editor de texto com validação de JSON e rascunho automático. Versões de AGP/Kotlin/Compose no `app/build.gradle.kts` foram escritas de memória: confira antes de compilar. Esperam-se pequenos erros de compilação. Ainda NÃO ligados à interface: editor de texturas, skins, geometria/animações e criador de Add-ons (a lógica existe em `:core`).
