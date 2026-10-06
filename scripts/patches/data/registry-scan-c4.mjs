// Flush-local Loader indexes. Error callbacks invalidate them; asynchronous typert
// registration still checks the live Loader, and package metadata keeps its existing lifetime.
export const CLIENT_SCAN_MARKER = 'dsh-mobile registry scan C4'
export const TYPERT_SCAN_MARKER = 'dsh-mobile typert scan C4'

function replaceOnce(source, before, after, label) {
  if (source.split(before).length !== 2) throw new Error(`registry-scan C4 anchor mismatch: ${label}`)
  return source.replace(before, after)
}

export function planClientRegistryScan(source) {
  if (source.includes(CLIENT_SCAN_MARKER)) return source
  source = replaceOnce(source, 'processOne(entryName, onError) {',
    'processOne(entryName, onError, entries = this.ctx.loader.entries()) {', 'client processOne')
  source = replaceOnce(source, 'for (const entry of this.ctx.loader.entries()) {\n\t\t\tif (entry.options.name !== entryName',
    'for (const entry of entries) {\n\t\t\tif (entry.options.name !== entryName', 'client row loop')
  source = replaceOnce(source, '\tflush(onError) {\n\t\tlet changed = false;', `\tflush(onError) {
		/* dsh-mobile registry scan C4: one Loader walk per unchanged flush batch. */
		let entriesByName;
		const batchEntries = () => {
			if (entriesByName !== void 0) return entriesByName;
			entriesByName = new Map();
			for (const entry of this.ctx.loader.entries()) {
				const name = entry.options.name;
				let rows = entriesByName.get(name);
				if (rows === void 0) entriesByName.set(name, rows = []);
				rows.push(entry);
			}
			return entriesByName;
		};
		const reportError = (error) => { entriesByName = void 0; onError(error); };
		let changed = false;`, 'client flush')
  source = replaceOnce(source, 'if (this.processOne(entryName, onError)) changed = true;',
    'if (this.processOne(entryName, reportError, batchEntries().get(entryName) ?? [])) changed = true;', 'client flush dispatch')
  source = replaceOnce(source, 'onError(error instanceof Error ? error : new Error(String(error)));\n\t\t\t}\n\t\t}\n\t\tif (!changed)',
    'reportError(error instanceof Error ? error : new Error(String(error)));\n\t\t\t}\n\t\t}\n\t\tif (!changed)', 'client flush error')
  source = replaceOnce(source, 'const pkg = JSON.parse(readFileSync(pkgPath, "utf8"));',
    'const pkg = located.manifest ?? JSON.parse(readFileSync(pkgPath, "utf8"));', 'client manifest reuse')
  source = replaceOnce(source, 'const name = JSON.parse(readFileSync(candidate, "utf8")).name;',
    'const manifest = JSON.parse(readFileSync(candidate, "utf8"));\n\t\t\t\tconst name = manifest.name;', 'client manifest locate')
  return replaceOnce(source, 'path: candidate,\n\t\t\t\t\tpackageName: name',
    'path: candidate,\n\t\t\t\t\tmanifest,\n\t\t\t\t\tpackageName: name', 'client located manifest')
}

export function planTypertRegistryScan(source) {
  if (source.includes(TYPERT_SCAN_MARKER)) return source
  source = replaceOnce(source, 'const qualifies = (entryName) => {\n\t\tif (configured.has(entryName)) return true;',
    'const qualifies = (entryName, mountedNames) => {\n\t\tif (configured.has(entryName)) return true;\n\t\tif (mountedNames !== void 0) return mountedNames.has(entryName);', 'typert qualifies')
  source = replaceOnce(source, 'const processOne = (entryName) => {\n\t\tif (!qualifies(entryName))',
    'const processOne = (entryName, mountedNames) => {\n\t\tif (!qualifies(entryName, mountedNames))', 'typert processOne')
  source = replaceOnce(source, 'const flush = (onError) => {\n\t\tconst tasks = [];', `const flush = (onError) => {
		/* dsh-mobile typert scan C4: snapshot only this synchronous flush; async registration rechecks live entries. */
		let mountedNames;
		const batchNames = () => {
			if (mountedNames !== void 0) return mountedNames;
			mountedNames = new Set();
			for (const entry of ctx.loader.entries()) if (entry.fiber !== void 0 && !entry.disabled) mountedNames.add(entry.options.name);
			return mountedNames;
		};
		const tasks = [];`, 'typert flush')
  source = replaceOnce(source, 'const task = processOne(entryName);',
    'const task = processOne(entryName, batchNames());', 'typert flush dispatch')
  return replaceOnce(source, '\t\t\t} catch (error) {\n\t\t\t\tonError(toError(error));\n\t\t\t}',
    '\t\t\t} catch (error) {\n\t\t\t\tmountedNames = void 0;\n\t\t\t\tonError(toError(error));\n\t\t\t}', 'typert flush error')
}
