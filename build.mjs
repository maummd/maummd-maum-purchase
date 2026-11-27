// web/index.html 한 파일로 묶습니다: node build.mjs
// LOCAL=1 이면 Firebase 없이 이 기기에만 저장하는 시험판으로 묶습니다 (→ web/local.html).
import { build } from "esbuild";
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from "node:fs";

const local = !!process.env.LOCAL;
const out = await build({
  entryPoints: ["src/store.js"], bundle: true, minify: true, format: "iife", target: ["es2020"], write: false,
  legalComments: "none",
  plugins: local ? [{ name: "local", setup(b) { b.onLoad({ filter: /config\.js$/ }, () => ({ contents: "export const FIREBASE_CONFIG = null;", loader: "js" })); } }] : [],
});
const js = out.outputFiles[0].text.replace(/<\/script/gi, "<\\/script");
const html = readFileSync("src/index.template.html", "utf8").replace("/*BUNDLE*/", () => js);
mkdirSync("web", { recursive: true });
const outFile = local ? "web/local.html" : "web/index.html";
writeFileSync(outFile, html);
// 주문표 불러오기 도구(pdf.js · fflate)를 앱 안에 함께 넣어요. (.js 이름이어야 안드로이드 앱에서도 열려요)
mkdirSync("web/lib", { recursive: true });
for (const [from, to] of [["node_modules/pdfjs-dist/legacy/build/pdf.min.mjs", "pdf.min.js"], ["node_modules/pdfjs-dist/legacy/build/pdf.worker.min.mjs", "pdf.worker.min.js"], ["node_modules/fflate/esm/browser.js", "fflate.js"]]) copyFileSync(from, "web/lib/" + to);
console.log(outFile, Math.round(html.length / 1024), "KB");
