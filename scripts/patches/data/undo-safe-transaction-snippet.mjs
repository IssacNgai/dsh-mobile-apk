// dsh-mobile safe mode transaction (S2): backups and state precede atomic per-file replacement.
function safeModeSha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
async function safeModeAtomicWrite(target, bytes) {
  const dir = dirname(target);
  await fs.mkdir(dir, { recursive: true });
  const temp = join(dir, `.${basename(target)}.safe-mode-${randomBytes(6).toString('hex')}.tmp`);
  try {
    await fs.writeFile(temp, bytes);
    await fs.rename(temp, target);
  } finally {
    await fs.rm(temp, { force: true }).catch(() => {});
  }
}
async function safeModeBackupPath(cfg, value, id, prefix, suffix = '.yml') {
  if (typeof value !== 'string' || value === '' || typeof id !== 'string' || id === '') return null;
  try {
    const root = await fs.realpath(cfg.autoDir);
    const candidate = resolve(value);
    const expectedName = `${prefix}${id}${suffix}`;
    if (basename(candidate) !== expectedName || await fs.realpath(dirname(candidate)) !== root) return null;
    // autoDir and stored paths may use a symlink alias; only allow that on the
    // parent path. The backup file itself must remain a regular, non-symlink file.
    const info = await fs.lstat(candidate);
    if (!info.isFile() || info.isSymbolicLink()) return null;
    const canonical = join(root, expectedName);
    if (await fs.realpath(candidate) !== canonical) return null;
    return canonical;
  } catch { return null; }
}
async function safeModeVerifyBackup(path, expectedSha) {
  if (!path) return false;
  try {
    const bytes = await fs.readFile(path);
    return typeof expectedSha !== 'string' || expectedSha === '' || safeModeSha256(bytes) === expectedSha;
  } catch { return false; }
}
async function safeModeSet(cfg, on, { atomicWrite = safeModeAtomicWrite } = {}) {
  if (hasOpenTurn()) return busyError();
  const st = await safeModeStatus(cfg);
  const patch = filePath(cfg, { root: 'profile', rel: 'cordis.patch.yml' });
  const homePatch = filePath(cfg, { root: 'home', rel: 'cordis.patch.yml' });
  const pkgPath = filePath(cfg, { root: 'profile', rel: 'package.json' });
  if (on) {
    if (st.active) {
      let rescanned = [];
      try {
        const pkg = JSON.parse(await fs.readFile(pkgPath, 'utf8'));
        rescanned = (await computeSafeBundles(cfg, pkg)).pruned;
      } catch { /* package.json 读不到则重扫结果为空 */ }
      return {
        ok: true, active: true,
        message: t('safe.alreadyOn', { entered: st.enteredAt ?? '?' })
          + (rescanned.length > 0
            ? t('safe.rescan.found', { n: rescanned.length, list: rescanned.map((p) => p.name).join(', ') })
            : t('safe.rescan.none')),
      };
    }
    if (await pathExists(join(cfg.autoDir, 'safe-mode.json'))) {
      const raw = await fs.readFile(join(cfg.autoDir, 'safe-mode.json'), 'utf8').catch(() => null);
      if (raw === null) return { ok: false, error: 'Safe-mode state is unreadable; refusing to overwrite recovery state.' };
      try { JSON.parse(raw); } catch { return { ok: false, error: 'Safe-mode state is corrupt; refusing to overwrite recovery state.' }; }
    }
    const snap = await createSnapshot(cfg, 'manual', 'safe-mode-before');
    const backup = join(cfg.autoDir, `safe-mode-backup-${snap.id}.yml`);
    const homeBackup = join(cfg.autoDir, `safe-mode-home-backup-${snap.id}.yml`);
    const pkgBackup = join(cfg.autoDir, `safe-mode-pkg-${snap.id}.json`);
    await fs.mkdir(cfg.autoDir, { recursive: true });
    const patchBytes = await fs.readFile(patch).catch((error) => {
      if (error?.code === 'ENOENT') return Buffer.from('[]\n');
      throw error;
    });
    const homePatchExists = await pathExists(homePatch);
    const homeBytes = homePatchExists ? await fs.readFile(homePatch) : null;
    let pkgBytes = null;
    let prunedBundles = [];
    let pkgSafeBytes = null;
    try { pkgBytes = await fs.readFile(pkgPath); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
    if (pkgBytes !== null) {
      const pkgRaw = pkgBytes.toString('utf8');
      try {
        const pkg = JSON.parse(pkgRaw);
        const { pruned, kept } = await computeSafeBundles(cfg, pkg);
        prunedBundles = pruned;
        const orig = pkg.dsh?.profile?.bundles ?? [];
        if (kept.join('\u0000') !== orig.join('\u0000')) {
          pkg.dsh = pkg.dsh ?? {};
          pkg.dsh.profile = pkg.dsh.profile ?? {};
          pkg.dsh.profile.bundles = kept;
          pkgSafeBytes = Buffer.from(JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        }
      } catch (error) {
        return { ok: false, error: t('safe.err.corruptPkg', { msg: String(error?.message ?? error) }) };
      }
    }
    try {
      await atomicWrite(backup, patchBytes);
      if (homeBytes) await atomicWrite(homeBackup, homeBytes);
      if (pkgBytes) await atomicWrite(pkgBackup, pkgBytes);
      if (!(await safeModeVerifyBackup(backup, safeModeSha256(patchBytes))) ||
        (homeBytes && !(await safeModeVerifyBackup(homeBackup, safeModeSha256(homeBytes)))) ||
        (pkgBytes && !(await safeModeVerifyBackup(pkgBackup, safeModeSha256(pkgBytes))))) {
        return { ok: false, error: t('safe.err.backupWrite', { backup }) };
      }
    } catch (error) {
      return { ok: false, error: t('safe.err.backupWrite', { backup }) + ` (${String(error?.message ?? error)})` };
    }
    // dsh-mobile safe keeps shipped plugins (S1): 只摘第三方 insert 子条目，保留我方装配的插件
    // 与全部顶层 disable 行（与壳侧 SafeMode.kt 同口径）。原实现整份覆写成最小文件，会摘掉
    // 12 个 @dsh-android/* 引用与 7 条 disabled（含安全关键的 client-hmr）。
    const dshMobileSafeShippedPrefixes = ["@deepseek-ai/", "@dsh-android/"];
    const dshMobileSafeShippedNames = ["dsh-undo-savepoint", "dshmarketplace-plugin"];
    const dshMobileSafeIsShipped = (name) => {
      const v = String(name ?? "").trim().replace(/^['\"]+|['\"]+$/g, "");
      if (v === "") return false;
      if (dshMobileSafeShippedNames.includes(v)) return true;
      return dshMobileSafeShippedPrefixes.some((p) => v.startsWith(p));
    };
    const dshMobileSafeFilterInserts = (text) => {
      const lines = String(text).split("\n");
      const out = [];
      let i = 0;
      while (i < lines.length) {
        if (!/^- insert:\s*$/.test(lines[i])) { out.push(lines[i]); i += 1; continue; }
        let end = i + 1;
        while (end < lines.length && !/^-/.test(lines[end])) end += 1;
        const body = lines.slice(i + 1, end);
        const firstItem = body.find((l) => /^(\s*)-\s+(id|name):/.test(l));
        const itemIndent = firstItem ? firstItem.length - firstItem.replace(/^\s+/, "").length : null;
        if (itemIndent === null) { out.push(lines[i]); out.push(...body); i = end; continue; }
        // 按缩进切子条目块
        const chunks = [];
        let cur = null;
        for (const line of body) {
          const m = /^(\s*)-\s+/.exec(line);
          if (m && m[1].length === itemIndent) { if (cur) chunks.push(cur); cur = [line]; }
          else if (cur) cur.push(line);
        }
        if (cur) chunks.push(cur);
        const kept = [];
        for (const chunk of chunks) {
          const nm = /^\s*-?\s*name:\s*['\"]?([^'\"\s]+)/m.exec(chunk.join("\n"));
          if (nm && !dshMobileSafeIsShipped(nm[1])) continue; // 第三方：整条摘掉
          kept.push(...chunk);
        }
        if (kept.length === 0) { i = end; continue; } // 空 insert 会让引擎 boot 抛
        out.push(lines[i]); out.push(...kept); i = end;
      }
      return out.join("\n");
    };
    const minimal = dshMobileSafeFilterInserts(patchBytes.toString('utf8'));
    const state = {
      active: true, enteredAt: new Date().toISOString(), backup, snapshotId: snap.id,
      backupSha256: safeModeSha256(patchBytes),
      homeBackup: homePatchExists ? homeBackup : undefined,
      homeBackupSha256: homeBytes ? safeModeSha256(homeBytes) : undefined,
      homeFingerprint: await homeFingerprint(cfg),
    };
    if (pkgBytes) {
      state.pkgBackup = pkgBackup;
      state.pkgBackupSha256 = safeModeSha256(pkgBytes);
    }
    if (prunedBundles.length > 0) state.prunedBundles = prunedBundles;
    const stateFile = join(cfg.autoDir, 'safe-mode.json');
    try {
      await atomicWrite(stateFile, Buffer.from(JSON.stringify(state, null, 2), 'utf8'));
      await atomicWrite(patch, Buffer.from(minimal, 'utf8'));
      if (homePatchExists) {
        await atomicWrite(homePatch, Buffer.from(`# dsh-undo-savepoint SAFE MODE (home level, entered ${new Date().toISOString()})\n[]\n`, 'utf8'));
      }
      if (pkgSafeBytes) await atomicWrite(pkgPath, pkgSafeBytes);
    } catch (error) {
      return { ok: false, active: true, error: `Safe Mode changes did not finish; recovery state and backups were retained. Retry safe-mode off after resolving the write error: ${String(error?.message ?? error)}` };
    }
    const prunedTxt = prunedBundles.length > 0
      ? t('safe.neutralized', { n: prunedBundles.length, list: prunedBundles.map((p) => `${p.name}（${p.reason}）`).join('；') })
      : '';
    let patchNote = '';
    try {
      const pv = await patchVerify(cfg);
      if (pv.ok === false && Array.isArray(pv.missing) && pv.missing.length > 0) {
        patchNote = t('safe.patchNote', { n: pv.missing.length, list: pv.missing.join(', ') });
      }
    } catch { /* 检测失败不影响安全模式 */ }
    return { ok: true, active: true, snapshotId: snap.id, prunedBundles, message: t('safe.on', { id: snap.id }) + prunedTxt + patchNote };
  }
  // off
  if (!st.active) {
    return st.stale
      ? { ok: true, active: false, message: t('safe.stale') }
      : { ok: true, active: false, message: t('safe.notActive') };
  }
  const backup = await safeModeBackupPath(cfg, st.backup, st.snapshotId, 'safe-mode-backup-');
  const homeBackup = st.homeBackup
    ? await safeModeBackupPath(cfg, st.homeBackup, st.snapshotId, 'safe-mode-home-backup-') : null;
  const pkgBackup = st.pkgBackup
    ? await safeModeBackupPath(cfg, st.pkgBackup, st.snapshotId, 'safe-mode-pkg-', '.json') : null;
  if (!st.active || !await safeModeVerifyBackup(backup, st.backupSha256)) {
    return { ok: false, error: 'Safe-mode backup missing. Restore a snapshot from before the crash first (undo_list / undo_restore).' };
  }
  if ((st.homeBackup && !await safeModeVerifyBackup(homeBackup, st.homeBackupSha256)) ||
    (st.pkgBackup && !await safeModeVerifyBackup(pkgBackup, st.pkgBackupSha256))) {
    return { ok: false, error: 'Safe-mode home backup missing. Restore a snapshot from before the crash first (undo_list / undo_restore).' };
  }
  try {
    await atomicWrite(patch, await fs.readFile(backup));
    if (homeBackup) await atomicWrite(homePatch, await fs.readFile(homeBackup));
    if (pkgBackup) await atomicWrite(pkgPath, await fs.readFile(pkgBackup));
    await fs.rm(join(cfg.autoDir, 'safe-mode.json'), { force: true });
  } catch (error) {
    return { ok: false, error: `Safe-mode restore incomplete; state and backups were retained for retry: ${String(error?.message ?? error)}` };
  }
  const pkgRestored = !!pkgBackup;
  const restoreTxt = pkgRestored
    ? t('safe.off.restorePkg', { n: st.prunedBundles?.length ?? 0 })
    : t('safe.off.legacy');
  return { ok: true, active: false, message: t('safe.off') + restoreTxt };
}
