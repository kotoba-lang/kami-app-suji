# kami-app-suji

**姿勢を動かすと、どこにどれだけ負荷がかかるかがその場で 3D と数値で出る。**
ブラウザの中で物理を解いて WebGPU で描く single-page app。サーバは要らない。

名前が機能を示さないので最初に名乗る（superproject `CLAUDE.md` の規約）。
**suji（筋）** は力の線であり筋肉であり、この app はその可視面である。

```
姿勢（関節角）
  → 前方運動学で 3 次元に配置        suji.methods.pose
  → 静的逆動力学（RNEA の重力項）      suji.methods.load
  → 頸椎圧縮荷重（Hansraj 2014 で検証）
  → 筋張力と %MVC（Hill 型モーメントアーム）  suji.methods.muscle
  → 作業時間ぶんの強張り（Rohmert）      suji.methods.strain
  → 描画シーン                        kami.app-suji.scene
  → WebGPU / WebGL 2.0                kami.webgpu.mesh
```

## この repo が持つもの、持たないもの

| | 正本 |
|---|---|
| 物理・姿勢・筋・強張り | **cloud-itonami/suji**（純 `.cljc`、ブラウザ内で動く） |
| メッシュ生成・GPU 実行 | **kotoba-lang/webgpu** + **kotoba-lang/webgl** |
| デザインシステム | **kotoba-lang/jp-go-digital-design-system**（DADS） |
| **UI と操作の組み立て** | **ここ** |

repo-wide の 3D 規約どおり、`kami-app-*` は UI と orchestration を持ち、形状・
シーン・シミュレーション・描画の正本を複製しない。この app は三角形を 1 つも
自分で生成せず、関節角から座標を 1 つも自分で導かない。

## なぜ今日まで作れなかったか

`suji` は `.cljc` を名乗りながら `Math/toRadians` / `Double/POSITIVE_INFINITY` /
`Double/isInfinite` を素で呼んでおり、**ClojureScript では 1 行も動かなかった**
（JVM のテストは緑だった —— JVM だけのスイートは、可搬な `.cljc` と可搬を名乗る
だけの `.cljc` に同じ緑を返す）。2026-09-06 にそれを直し、cljs 側でも走る runner を
足したのが、この app の前提条件だった。`suji` の `shoulder-moment` の幾何も同じ日に
直っている（真下にぶら下げた腕が 5.15 N·m を出していた）。

## 動かす

```bash
npm install
npm run page                # public/index.html と 404.html を生成
npm run release             # shadow-cljs release → public/js/main.js
(cd public && python3 -m http.server 8741)
PW_CHANNEL=chrome npm run verify:browser
```

### 検証

| 何を | どう | 結果 |
|---|---|---|
| scene / route（純 `.cljc`） | `clojure -M:test` | 17 tests / 126 assertions |
| 実ブラウザ | `scripts/verify-browser.cljs` | 16 checks |
| lint | `clojure -M:lint` | 0 errors / 0 warnings |

⚠ **この表は 2026-09-06 まで初回の値（10 / 86 / 8）のまま止まっていた。** 更新している
つもりで書いた置換が一致せず、黙って何もしていなかった —— 一致を検証しない編集は、
編集しなかったことと区別がつかない。数を書くならその場で測ること。

ブラウザ側の検査は、コンパイラにもテストにも見えないものだけを見る:

1. **GPU backend を実際に取れたか。** `init-canvas!` は WebGPU から WebGL 2.0 へ
   黙って落ちる。落ちた app と落ちなかった app は見た目が同じなので、ページが
   取れた backend を名乗り、それを読む。
2. **画素が描かれたか。** 初期化が全部成功して真っ黒、は正しく見える失敗である。
   canvas をサンプルして単色でないことを要求する。
3. **物理がブラウザで走るか。** スライダを動かすと数値が変わること。
   2026-09-06 より前のどの commit でもこの assertion は通らなかった。
4. **view を跨ぐときに document を読み込まないか。** single page の主張であり、
   ソースからは観測できない（nav が router link でも素の href でもコードは同じ）。
   `window` に値を置いて跨ぎ、残っていることを見る。

evidence floor つき —— checks が 7 本未満なら exit **2**（0 でも 1 でもない）で
「答えられなかった」と言って終わる。

## 実測した罠

**`mesh/render-scene!` は「複数メッシュ」と書いてあるが、1 つの upload を使い回すと
最後の 1 つしか描かれない。** per-draw の uniform（MVP と色）を `upload-mesh!` が
返したバッファに書き込むので、pass 内の全 draw がエンコードされてから実行される
以上、共有した N 個の draw は最後の uniform を読む。**エラーは出ず、もっともらしい
絵が出る。** 6 本の骨が 1 本の棒として描かれた（2026-09-06 実測）。この app は
オブジェクトごとにバッファを持つ（`viewport/bone-slots` / `joint-slots`）。

**React 18 の `createRoot().render()` は非同期にコミットする。** mount 直後の
`requestAnimationFrame` 1 回では canvas がまだ無く、`when-let` は静かに何もしない
—— 再試行が無ければ viewport は一生初期化されない。エラーはどこにも出ず、canvas は
既定の 300×150 のまま残る。いまは毎 render 後に `ensure-canvas!` を呼ぶ。

## 境界（憲章）

- **G1 非診断（医師法 §17）** — 出力は力学量だけ（N·m / N / %MVC / kgf / 無次元の
  強張り指数）。診断・疾患・処方・治療を表現できるフィールドは、この app にも
  `suji` の schema にも無い。
- **G2 シミュレーションのみ（薬機法）** — 計測ハードウェアを持たない。入力は姿勢の
  パラメータであって生体計測ではない。
- **G3 自己参照** — 同一の身体を設定間で比べる。人と人を並べない。
- **G7 出所に正直** — 人体計測は Winter / Drillis の回帰、PCSA とモーメントアームは
  代表値。個人の測定値ではない。

## いま無いもの（正直に）

- 矢状面 2 次元の連鎖である。前額面・回旋を持たない。
- 冗長筋の静的最適化（Crowninshield–Brand 型）を持たない。1 関節 1 筋の直接割当。
- モーメントアームは角度に依存しない定数。
- 骨の形状は円柱であって解剖学的メッシュではない（`biomech` の roadmap でも
  anatomical mesh ingestion は未実装）。
- `route.cljc` はこのワークスペースで **3 つ目**の同型のコピーであり、`kotoba-uiux`
  skill が言う抽出の trigger は既に引かれている。抽出と既存 2 app の移行は別変更。
