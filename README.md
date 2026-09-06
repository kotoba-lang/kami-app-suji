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
  → 起始・停止から幾何で出したモーメントアーム  suji.methods.attachment
  → 拮抗筋間の荷重配分（Crowninshield-Brand） suji.methods.recruit
  → 筋張力と %MVC（Hill 型長さ-張力）         suji.methods.muscle
  → 作業時間ぶんのドーズ（べき則）           suji.methods.strain
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

`npm run page` は DADS の `dds.css` を探す。west checkout の外で走らせるなら
`DDS_ROOT=<jp-go-digital-design-system の場所>` を渡す。

### 検証

| 何を | どう | 結果 |
|---|---|---|
| 純 `.cljc` の view / scene / coverage / route | `clojure -M:test` | 60 tests / 1,505 assertions |
| 同じものを **ClojureScript で** | `npm run check:cljs` | 56 tests / 1,489 assertions |
| 実ブラウザ | `scripts/verify-browser.cljs` | 40 checks（ローカルと公開 URL） |
| lint | `clojure -M:lint` | 0 errors / 0 warnings |

⚠ **2 行目は 2026-09-08 まで存在しなかった。** この app の view も scene も
coverage も `.cljc` でありながら、検査していたのは JVM だけだった ——
`suji` が「`.cljc` を名乗りながら ClojureScript では 1 行も動かなかった」という、
この README が上で書いている欠陥の、この repo 自身での再演である。
足した日の 1 回目で実際に落ちた（`coverage` の `digit?` が JVM とブラウザで
違う答えを返していた）。**片方の host だけの緑は、可搬な `.cljc` と可搬を名乗る
だけの `.cljc` に同じ色を返す。**

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
5. **ページが名乗っている被覆率は計算されたものか。** 検査器が同じ `.cljc` で
   census を計算し直してページの 3 つの数と突き合わせる。骨メッシュの検査と同じ形
   —— ページが自分の答案を採点しない。

evidence floor つき —— checks が 39 本未満なら exit **2**（0 でも 1 でもない）で
「答えられなかった」と言って終わる。

## モデルが出した量のうち、どれだけが人に届くか

**`kami.app-suji.coverage` がそれを数える。** `suji` は 1 姿勢あたり数百の数を出し、
このページはその一部を描く。**モデルが量を 1 つ増やしてページが何もしないことを、
コンパイラもテストも見つけられない** —— 新しいキーは誰も読まない map に座るだけで、
すべては緑のままモデルより小さい問いに答え続ける。

3 つのうち 2 つは**導出**で、手で書くのは分類だけ:

| | 何を | どう |
|---|---|---|
| `produced` | この app の解が出す全量 | 実際の解を歩く |
| `registry` | 各量が出ているか | 手で書く。これが主張 |
| `unrequested` | どの view も呼んでいない `suji` の入口が持つ量 | 呼んで数える（下限） |

**`:shown` は信用されない。** 6 姿勢で全 view を描画し、その量自身の値を
value-bearing な要素（表のセル、readout の figure、`<strong>`）の中に探す ——
平坦化したページ本文ではない。この repo は探していたものの**隣にある散文**に
当たって通った検査を 3 回記録している。

判定は**差**である。ある姿勢で取る値を別の姿勢では取らないとき、その値が前者で
見つかり後者で見つからないことを要求する。偶然の一致は姿勢が動いても動かないので、
両方で見つかる。閾値は要らない（試して捨てた —— 描画されている列は 1.0、
`active-n` は受動張力 0.5 N 超の行にしか出ないので 0.5 前後、両方通す閾値は
偶然も通す。1.37 倍した値を対照にする方法も試したが、300 個の数が載ったページは
「この数はここにあるか」という形の問いにほぼ何でも肯定する）。

`the-probe-says-no-when-a-view-is-removed` が、これが飾りでないことを保つ ——
simulator を外して描画したページに同じ probe を当て、そこにしか無い量が absent に、
spine view の量が found のままであることを両方 assert する。

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

⚠ **この節は 2026-09-07 まで、5 項目のうち 5 項目が偽だった。** どれも「無い」と
書いてあるものが実際には在り、しかも在るようになった日から一度も直されていなかった。
実測して置き換えた記録:

| 書いてあったこと | 実測 |
|---|---|
| 矢状面 2 次元、前額面・回旋を持たない | 両側 3 次元。前額面軸の筋 5 群、頭部回旋の入力あり |
| Crowninshield-Brand を持たない、1 関節 1 筋の直接割当 | `suji.methods.recruit/share` がそれである |
| モーメントアームは角度に依存しない定数 | 幾何から導出。頸部伸筋は頭部前屈 0→60° で 0.020043 → 0.012000 m |
| 骨は円柱であって解剖学的メッシュではない | 10 種の解剖形状。円柱で描かれる骨は 1 本も無い |
| `route.cljc` は 3 つ目の同型コピー | `kotoba-lang/route` に抽出済み。ここに残るのは 22 行の view 表 |

`method-view` の同じ節も同じ日に同じ理由で偽になっていたので、あちらは**数と一覧を
モデルから計算する**ようにした。README は静的ファイルなので計算できない代わりに、
`the-readme-does-not-call-absent-what-the-model-has` が「無い」と書かれたものが
本当に無いかを検査する。

⚠ **6 項目めが 2026-09-08 に偽になった。** ここには
「**複数制約の同時解**。`recruit` の閉形式は等式制約を 1 本しか取らないので、
2 関節筋の他関節モーメントは計算して報告するだけ。環椎後頭では、その未処理分が
それを吸収すべき解剖の総容量の 1.85 倍ある」と書いてあった。`suji` が
`recruit/solve`（複数の等式制約を同時に満たす Crowninshield–Brand）を入れ、
首の 2 関節と片脚の 3 関節はいま**同時に解かれている** —— 残差は 10⁻¹¹ N·m 台で、
その数はページに出ている。1.85 倍を報告していた `:surplus-mvc-pct` ごと消えた。

**この節が偽になった経緯を、今度は機械で捕まえる。** 上の 5 件と同じく、
`the-readme-does-not-call-absent-what-the-model-has` がこの主張も検査する
（`recruit/solve` が在れば「閉形式は 1 本しか取らない」と書けない）。

いま本当に無いもの:

- **後頭下筋のうち下頭斜筋**。両端が `upper_cervical` に乗る。環軸関節が要る。
  他の 3 つ（大後頭直筋・小後頭直筋・上頭斜筋）は解かれている。
- **C2/C3 と C7 で解かれる方程式**。連立解が満たすのは環椎後頭・C7・股・膝・足の
  5 つで、`c2c3` と（肩甲帯懸垂の側から見た）`c7` にはそもそも解くべき平衡が無い。
  そこを跨ぐ筋のモーメントは「どの式にも入っていない」として報告される。
  阻んでいるのは分節化ではなく出典で、集中定数 `cervical_extensors` の
  12.0 cm² 自体に出典が無い。
- **下位頸椎 5 レベルと腰椎 5 レベルは、それぞれ 1 つの向きを共有する。**
- 筋の付着は点であって、複数椎骨にまたがる面ではない。
