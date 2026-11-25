// 물품 구매 관리 저장소: Firebase(실사용, 여러 휴대폰이 함께 씀) 또는 기기 전용 시험 모드.
// 화면(index.template.html)은 window.MP_STORE 만 사용합니다.
//
// Firestore 구조
//   users/{uid}                     { orgId, name }                  내가 속한 기관
//   joinCodes/{code}                { orgId }                         참여 코드 → 기관
//   orgs/{orgId}                    { name, owner, joinCode, settings, items, routine, checks, stock, updated }
//   orgs/{orgId}/members/{uid}      { name, email, roles[], active, code }
//   orgs/{orgId}/requests/{id}      직원 수요 (by = 낸 사람 uid)
//   orgs/{orgId}/orders/{id}        주문서 (stage = review → final → pay → ship → arrived → done)
//   orgs/{orgId}/surveys/{id}       수요조사
//   orgs/{orgId}/secret/paykey      { salt, hash }   결제 방지 키 (구매 담당 · 원장님만 읽기)
import { initializeApp } from "firebase/app";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  signOut, sendEmailVerification, sendPasswordResetEmail,
} from "firebase/auth";
import {
  initializeFirestore, persistentLocalCache, doc, setDoc, getDoc, deleteDoc, collection,
  onSnapshot, writeBatch, query, where,
} from "firebase/firestore";
import { FIREBASE_CONFIG } from "./config.js";

const clean = d => JSON.parse(JSON.stringify(d ?? null)); // undefined 제거
const MANAGER = ["admin", "mid", "final", "buyer"];
const isManager = roles => (roles || []).some(r => MANAGER.includes(r));
const newCode = () => { const c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; let s = ""; const a = crypto.getRandomValues(new Uint8Array(6)); a.forEach(x => s += c[x % c.length]); return s; };

// ---------- Firebase ----------
function firebaseStore(config) {
  const app = initializeApp(config);
  const auth = getAuth(app);
  let db;
  try { db = initializeFirestore(app, { localCache: persistentLocalCache() }); }
  catch (e) { db = initializeFirestore(app, {}); }
  let uid = null, email = "";
  const O = id => doc(db, "orgs", id);

  return {
    mode: "firebase",
    onAuth(cb) {
      return onAuthStateChanged(auth, async u => {
        if (!u) { uid = null; return cb(null); }
        if (!u.emailVerified) { try { await u.reload(); if (auth.currentUser && auth.currentUser.emailVerified) await auth.currentUser.getIdToken(true); } catch (e) { } }
        const cur = auth.currentUser || u;
        uid = cur.uid; email = (cur.email || "").toLowerCase();
        cb({ uid, email, verified: !!cur.emailVerified });
      });
    },
    signIn: (e, p) => signInWithEmailAndPassword(auth, e, p),
    signUp: (e, p) => createUserWithEmailAndPassword(auth, e, p),
    signOut: () => signOut(auth),
    verify: () => auth.currentUser && sendEmailVerification(auth.currentUser),
    reset: e => sendPasswordResetEmail(auth, e),
    async refresh() {
      const u = auth.currentUser; if (!u) return false;
      await u.reload(); await auth.currentUser.getIdToken(true);
      return !!auth.currentUser.emailVerified;
    },
    async loadMe() { const s = await getDoc(doc(db, "users", uid)); return s.exists() ? s.data() : null; },

    // 새 기관 만들기: 만든 사람 = 관리자
    async createOrg({ orgName, myName, roles, seed }) {
      try { await auth.currentUser.getIdToken(true); } catch (e) { } // 이메일 인증 정보를 새로 받기
      const orgId = doc(collection(db, "orgs")).id, code = newCode();
      const b = writeBatch(db);
      b.set(O(orgId), clean({ name: orgName, owner: uid, joinCode: code, ...seed, updated: Date.now() }));
      b.set(doc(db, "orgs", orgId, "members", uid), clean({ name: myName, email, roles: Array.from(new Set(["admin", ...roles])), active: true, at: Date.now() }));
      b.set(doc(db, "joinCodes", code), { orgId });
      b.set(doc(db, "users", uid), { orgId, name: myName });
      await b.commit();
      return orgId;
    },
    // 참여 코드로 들어가기: 처음엔 '직원' 역할
    async joinOrg(code, myName) {
      try { await auth.currentUser.getIdToken(true); } catch (e) { }
      code = String(code || "").trim().toUpperCase();
      const s = await getDoc(doc(db, "joinCodes", code));
      if (!s.exists()) throw new Error("참여 코드가 맞지 않아요");
      const orgId = s.data().orgId;
      const b = writeBatch(db);
      b.set(doc(db, "orgs", orgId, "members", uid), { name: myName, email, roles: ["staff"], active: true, code, at: Date.now() });
      b.set(doc(db, "users", uid), { orgId, name: myName });
      await b.commit();
      return orgId;
    },
    async leaveOrg() { await deleteDoc(doc(db, "users", uid)); },

    // 기관 자료를 실시간으로 받습니다. 역할에 따라 볼 수 있는 것만 듣습니다.
    listen(orgId, onData, onErr) {
      const unsub = [];
      let roles = [], rolesKey = null;
      const sub = (ref, key, many) => unsub.push(onSnapshot(ref, s => {
        if (many) onData({ [key]: s.docs.map(x => ({ id: x.id, ...x.data() })) });
        else onData({ [key]: s.exists() ? { id: s.id, ...s.data() } : null });
      }, e => onErr && onErr(e, key)));
      const startRest = () => {
        sub(O(orgId), "org");
        sub(collection(db, "orgs", orgId, "surveys"), "surveys", true);
        if (isManager(roles)) {
          sub(collection(db, "orgs", orgId, "requests"), "requests", true);
          sub(collection(db, "orgs", orgId, "orders"), "orders", true);
          sub(collection(db, "orgs", orgId, "members"), "members", true);
        } else {
          sub(query(collection(db, "orgs", orgId, "requests"), where("by", "==", uid)), "requests", true);
          onData({ orders: [], members: [] });
        }
      };
      // 내 역할이 바뀌면 다시 듣습니다
      const mu = onSnapshot(doc(db, "orgs", orgId, "members", uid), s => {
        const me = s.exists() ? { id: uid, ...s.data() } : null;
        const nr = me ? JSON.stringify(me.roles || []) : "none";
        onData({ me });
        if (me && nr !== rolesKey) { while (unsub.length) unsub.pop()(); rolesKey = nr; roles = me.roles || []; startRest(); }
      }, e => onErr && onErr(e, "me"));
      return () => { mu(); while (unsub.length) unsub.pop()(); };
    },
    saveOrg: (orgId, patch) => setDoc(O(orgId), clean({ ...patch, updated: Date.now() }), { merge: true }),
    put: (orgId, col, id, data) => setDoc(doc(db, "orgs", orgId, col, id), clean(data)),
    del: (orgId, col, id) => deleteDoc(doc(db, "orgs", orgId, col, id)),
    async getPayKey(orgId) { const s = await getDoc(doc(db, "orgs", orgId, "secret", "paykey")); return s.exists() ? s.data() : null; },
    setPayKey: (orgId, v) => setDoc(doc(db, "orgs", orgId, "secret", "paykey"), { ...v, at: Date.now(), by: uid }),
    async newJoinCode(orgId, old) {
      const code = newCode(), b = writeBatch(db);
      b.set(doc(db, "joinCodes", code), { orgId });
      if (old) b.delete(doc(db, "joinCodes", old));
      b.set(O(orgId), { joinCode: code, updated: Date.now() }, { merge: true });
      await b.commit(); return code;
    },
  };
}

// ---------- 기기 전용 시험 모드 (서버 없음) ----------
function localStore() {
  const K = "mp-local-v2";
  let D; try { D = JSON.parse(localStorage.getItem(K)) || {}; } catch (e) { D = {}; }
  const persist = () => { try { localStorage.setItem(K, JSON.stringify(D)); } catch (e) { } };
  let listeners = [];
  const emitAll = () => {
    const o = D.orgs && D.orgs[D.orgId]; if (!o) return;
    const arr = c => Object.entries(o[c] || {}).map(([id, v]) => ({ id, ...v }));
    const me = { id: "local", ...(o.members || {}).local };
    const all = { me, org: { id: D.orgId, ...o.doc }, surveys: arr("surveys"), requests: arr("requests"), orders: arr("orders"), members: arr("members") };
    listeners.forEach(f => f(clean(all)));
  };
  const emit = () => setTimeout(emitAll, 0);
  return {
    mode: "local",
    onAuth(cb) { setTimeout(() => cb({ uid: "local", email: "기기 전용", verified: true }), 0); return () => { }; },
    signIn: async () => { }, signUp: async () => { }, signOut: async () => { }, verify: async () => { }, reset: async () => { }, refresh: async () => true,
    async loadMe() { return D.orgId ? { orgId: D.orgId, name: D.name } : null; },
    async createOrg({ orgName, myName, roles, seed }) {
      try { await auth.currentUser.getIdToken(true); } catch (e) { } // 이메일 인증 정보를 새로 받기
      const orgId = "local-org"; D.orgId = orgId; D.name = myName;
      D.orgs = { [orgId]: { doc: { name: orgName, owner: "local", joinCode: "TEST01", ...clean(seed) }, members: { local: { name: myName, email: "기기 전용", roles: ["admin", "staff", "mid", "final", "buyer"], active: true } } } };
      persist(); return orgId;
    },
    async joinOrg() { throw new Error("시험 모드에서는 참여 코드를 쓸 수 없어요"); },
    async leaveOrg() { D = {}; persist(); },
    listen(orgId, onData) { const f = onData; listeners.push(f); emit(); return () => { listeners = listeners.filter(x => x !== f); }; },
    async saveOrg(orgId, patch) { const o = D.orgs[orgId]; o.doc = { ...o.doc, ...clean(patch) }; persist(); emit(); },
    async put(orgId, col, id, data) { const o = D.orgs[orgId]; (o[col] = o[col] || {})[id] = clean(data); persist(); emit(); },
    async del(orgId, col, id) { const o = D.orgs[orgId]; if (o[col]) delete o[col][id]; persist(); emit(); },
    async getPayKey(orgId) { return D.orgs[orgId].paykey || null; },
    async setPayKey(orgId, v) { D.orgs[orgId].paykey = v; persist(); },
    async newJoinCode(orgId) { const c = newCode(); D.orgs[orgId].doc.joinCode = c; persist(); emit(); return c; },
    // 시험용: 내 역할 바꾸기
    async setMyRoles(roles) { D.orgs[D.orgId].members.local.roles = roles; persist(); emit(); },
  };
}

window.MP_STORE = FIREBASE_CONFIG ? firebaseStore(FIREBASE_CONFIG) : localStore();
