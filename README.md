# 물품 구매 관리 (com.maumm.purchase)

구의연세정신건강의학과 외래 병원 · 센터 · 브레인 물품 구매 업무 앱입니다. (개발: maumm.org)

## 결재 라인
직원 수요 제출 → 중간관리자 검수 → 최종관리자(원장님) 결재 → 구매 담당 결제(결제 방지 키) → 중간관리자 수령 검수

- 직원: 앱 설치 → 이메일 가입 → 참여 코드 입력 → 수요 탭에서 수요조사 참여
- 관리자: 설정 → 구성원에서 역할(중간관리자 · 최종관리자 · 구매 담당) 지정
- 서버: Firebase (Auth 이메일 인증 + Firestore). 보안 규칙은 `firestore.rules`

## 빌드
- 화면: `src/index.template.html` + `src/store.js` → `node build.mjs` → `web/index.html`
- 시험판(서버 없이): `LOCAL=1 node build.mjs` → `web/local.html`
- 안드로이드: GitHub Actions (`.github/workflows/android.yml`)
