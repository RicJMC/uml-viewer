import fs from "fs";
import path from "path";
import ts from "typescript";

const SKIP_DIRS = new Set([
  "node_modules", "dist", "build", ".git", ".uml-viewer", "coverage",
  "tests", "test", "__tests__", ".venv", "venv",
]);

function skipFile(file) {
  const parts = file.split(path.sep);
  if (parts.some((part) => SKIP_DIRS.has(part))) return true;
  const base = path.basename(file);
  return base.endsWith(".test.ts") || base.endsWith(".test.tsx")
    || base.endsWith(".spec.ts") || base.endsWith(".spec.tsx")
    || base.endsWith(".d.ts");
}

function walkTs(dir, out) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walkTs(full, out);
    } else if (entry.isFile() && (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) && !skipFile(full)) {
      out.push(full);
    }
  }
}

function displayName(idStr) {
  const last = idStr.split(".").pop() || idStr;
  return last.split(/[-_]/).filter(Boolean).map((bit) => bit.charAt(0).toUpperCase() + bit.slice(1)).join("") || last;
}

function moduleOf(file, srcRoot, prefix) {
  let rel = path.relative(srcRoot, file).replace(/\\/g, "/");
  rel = rel.replace(/\.(tsx|ts)$/, "");
  if (rel.endsWith("/index")) rel = rel.slice(0, -"/index".length);
  if (rel === "index") rel = "";
  const dotted = rel.split("/").filter(Boolean).join(".");
  const ns = prefix ? (dotted ? `${prefix}.${dotted}` : prefix) : dotted;
  let id = ns;
  if (prefix && (ns === prefix || ns.startsWith(prefix + "."))) {
    id = ns === prefix ? prefix : ns.slice(prefix.length + 1);
  }
  id = id.replace(/\//g, ".");
  return { id, ns, name: displayName(id) };
}

function loadConfig(start) {
  const found = ts.findConfigFile(start, ts.sys.fileExists, "tsconfig.json");
  if (!found) return { paths: {}, baseUrl: start, dir: start };
  const read = ts.readConfigFile(found, ts.sys.readFile);
  const dir = path.dirname(found);
  const options = (read.config && read.config.compilerOptions) || {};
  const baseUrl = options.baseUrl ? path.resolve(dir, options.baseUrl) : dir;
  return { paths: options.paths || {}, baseUrl, dir };
}

function matchPaths(spec, paths, baseUrl) {
  for (const [pattern, targets] of Object.entries(paths)) {
    const list = Array.isArray(targets) ? targets : [targets];
    const star = pattern.indexOf("*");
    if (star === -1) {
      if (spec === pattern) return path.resolve(baseUrl, list[0].replace(/\*/g, ""));
    } else {
      const pre = pattern.slice(0, star);
      const post = pattern.slice(star + 1);
      if (spec.startsWith(pre) && spec.endsWith(post)) {
        const mid = spec.slice(pre.length, spec.length - post.length);
        return path.resolve(baseUrl, list[0].replace("*", mid));
      }
    }
  }
  return null;
}

function resolveFile(candidate) {
  const exts = [".ts", ".tsx", ".js", ".jsx"];
  if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  for (const ext of exts) {
    if (fs.existsSync(candidate + ext)) return candidate + ext;
  }
  for (const ext of exts) {
    const index = path.join(candidate, "index" + ext);
    if (fs.existsSync(index)) return index;
  }
  return null;
}

function packageName(spec) {
  if (spec.startsWith("@")) {
    const [scope, name] = spec.split("/");
    return name ? `${scope}.${name}` : scope;
  }
  return spec.split("/")[0];
}

function resolveSpec(fromFile, spec, ctx) {
  if (spec.startsWith(".")) {
    const file = resolveFile(path.resolve(path.dirname(fromFile), spec));
    return file ? { file } : { foreign: spec };
  }
  const aliased = matchPaths(spec, ctx.paths, ctx.baseUrl);
  if (aliased) {
    const file = resolveFile(aliased);
    return file ? { file } : { foreign: packageName(spec) };
  }
  return { foreign: packageName(spec) };
}

function isFn(node) {
  return ts.isFunctionDeclaration(node) || ts.isFunctionExpression(node) || ts.isArrowFunction(node)
    || ts.isMethodDeclaration(node) || ts.isConstructorDeclaration(node)
    || ts.isGetAccessor(node) || ts.isSetAccessor(node);
}

function complexity(node) {
  let cc = 1;
  function walk(n, nested) {
    if (!n || typeof n.kind !== "number") return;
    if (nested && isFn(n)) return;
    switch (n.kind) {
      case ts.SyntaxKind.IfStatement:
      case ts.SyntaxKind.ForStatement:
      case ts.SyntaxKind.ForInStatement:
      case ts.SyntaxKind.ForOfStatement:
      case ts.SyntaxKind.WhileStatement:
      case ts.SyntaxKind.DoStatement:
      case ts.SyntaxKind.CatchClause:
      case ts.SyntaxKind.ConditionalExpression:
      case ts.SyntaxKind.CaseClause:
        cc += 1;
        break;
      case ts.SyntaxKind.BinaryExpression:
        if (n.operatorToken && (
          n.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken
          || n.operatorToken.kind === ts.SyntaxKind.BarBarToken
          || n.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken)) {
          cc += 1;
        }
        break;
      default:
        break;
    }
    ts.forEachChild(n, (child) => walk(child, true));
  }
  walk(node.body || node, false);
  return cc;
}

function propName(name) {
  if (!name) return null;
  if (ts.isIdentifier(name) || ts.isStringLiteral(name) || ts.isNumericLiteral(name)) return name.text;
  if (ts.isPrivateIdentifier(name)) return name.text;
  return null;
}

function isPrivateName(name, modifiers) {
  if (!name) return false;
  if (name.startsWith("#") || name.startsWith("_")) return true;
  return (modifiers || []).some((mod) => mod.kind === ts.SyntaxKind.PrivateKeyword);
}

function hasExportModifier(node) {
  return (node.modifiers || []).some((mod) => mod.kind === ts.SyntaxKind.ExportKeyword);
}

function heritageKind(clause) {
  if (clause.token === ts.SyntaxKind.ImplementsKeyword) return "implements";
  if (clause.token === ts.SyntaxKind.ExtendsKeyword) return "extends";
  return null;
}

function exprText(expr) {
  if (!expr) return null;
  if (ts.isIdentifier(expr)) return expr.text;
  if (ts.isPropertyAccessExpression(expr)) {
    const left = exprText(expr.expression);
    return left ? `${left}.${expr.name.text}` : expr.name.text;
  }
  return null;
}

function parseFile(file, text) {
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true,
    file.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const imports = [];
  const bindings = new Map();
  const classes = [];
  const members = [];
  const flags = { iface: 0, typeAlias: 0, en: 0, abs: 0, concrete: 0, value: 0 };

  function addImport(spec, names, namespace) {
    if (!spec) return;
    imports.push(spec);
    for (const item of names) bindings.set(item.local, { spec, exported: item.exported, namespace: false });
    if (namespace) bindings.set(namespace, { spec, exported: null, namespace: true });
  }

  function visit(node) {
    if (ts.isImportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const spec = node.moduleSpecifier.text;
      const names = [];
      let namespace = null;
      const clause = node.importClause;
      if (clause) {
        if (clause.name) names.push({ local: clause.name.text, exported: "default" });
        const named = clause.namedBindings;
        if (named && ts.isNamespaceImport(named)) namespace = named.name.text;
        if (named && ts.isNamedImports(named)) {
          for (const el of named.elements) {
            names.push({ local: el.name.text, exported: (el.propertyName || el.name).text });
          }
        }
      }
      addImport(spec, names, namespace);
    } else if (ts.isExportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      imports.push(node.moduleSpecifier.text);
    } else if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const arg = node.arguments[0];
      if (arg && ts.isStringLiteral(arg)) imports.push(arg.text);
    } else if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "require") {
      const arg = node.arguments[0];
      if (arg && ts.isStringLiteral(arg)) imports.push(arg.text);
    }

    if (ts.isInterfaceDeclaration(node) && hasExportModifier(node)) flags.iface += 1;
    if (ts.isTypeAliasDeclaration(node) && hasExportModifier(node)) flags.typeAlias += 1;
    if (ts.isEnumDeclaration(node) && hasExportModifier(node)) flags.en += 1;
    if (ts.isFunctionDeclaration(node) && node.name && hasExportModifier(node)) flags.value += 1;
    if (ts.isVariableStatement(node) && hasExportModifier(node)) flags.value += 1;

    if ((ts.isClassDeclaration(node) || ts.isClassExpression(node)) && node.name) {
      const abstract = (node.modifiers || []).some((mod) => mod.kind === ts.SyntaxKind.AbstractKeyword);
      const exported = hasExportModifier(node);
      if (exported) {
        if (abstract) flags.abs += 1;
        else flags.concrete += 1;
      }
      const bases = [];
      for (const clause of node.heritageClauses || []) {
        const kind = heritageKind(clause);
        for (const typ of clause.types) {
          const text = exprText(typ.expression);
          if (kind && text) bases.push({ kind, text });
        }
      }
      classes.push({ name: node.name.text, bases, abstract, exported });
      members.push({
        name: node.name.text,
        private: isPrivateName(node.name.text, node.modifiers) || !exported,
        complexity: 1,
        line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1,
        endLine: sf.getLineAndCharacterOfPosition(node.end).line + 1,
      });
      for (const member of node.members) {
        if (ts.isMethodDeclaration(member) || ts.isConstructorDeclaration(member)
            || ts.isGetAccessor(member) || ts.isSetAccessor(member)) {
          const method = propName(member.name) || (ts.isConstructorDeclaration(member) ? "constructor" : null);
          if (!method) continue;
          members.push({
            name: `${node.name.text}.${method}`,
            private: isPrivateName(method, member.modifiers),
            complexity: complexity(member),
            line: sf.getLineAndCharacterOfPosition(member.getStart(sf)).line + 1,
            endLine: sf.getLineAndCharacterOfPosition(member.end).line + 1,
          });
        }
      }
    }

    if (ts.isFunctionDeclaration(node) && node.name) {
      const exported = hasExportModifier(node);
      members.push({
        name: node.name.text,
        private: !exported || isPrivateName(node.name.text, node.modifiers),
        complexity: complexity(node),
        line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1,
        endLine: sf.getLineAndCharacterOfPosition(node.end).line + 1,
      });
    }

    if (ts.isVariableStatement(node)) {
      const exported = hasExportModifier(node);
      for (const decl of node.declarationList.declarations) {
        if (!ts.isIdentifier(decl.name) || !decl.initializer) continue;
        if (!(ts.isArrowFunction(decl.initializer) || ts.isFunctionExpression(decl.initializer))) continue;
        members.push({
          name: decl.name.text,
          private: !exported || isPrivateName(decl.name.text, node.modifiers),
          complexity: complexity(decl.initializer),
          line: sf.getLineAndCharacterOfPosition(decl.getStart(sf)).line + 1,
          endLine: sf.getLineAndCharacterOfPosition(decl.end).line + 1,
        });
      }
    }

    ts.forEachChild(node, visit);
  }
  visit(sf);
  return { imports, bindings, classes, members, flags };
}

function stereotype(flags) {
  const { iface, typeAlias, en, abs, concrete, value } = flags;
  if (concrete || value) return null;
  if (abs) return "abstract";
  if (en && !iface && !typeAlias && !abs) return "enumeration";
  if ((iface || typeAlias) && !en && !abs) return "interface";
  return null;
}

function fileIndex(srcRoot, prefix) {
  const files = [];
  walkTs(srcRoot, files);
  const byFile = new Map();
  const byNs = new Map();
  for (const file of files) {
    const mod = moduleOf(file, srcRoot, prefix);
    if (!mod.ns) continue;
    const text = fs.readFileSync(file, "utf8");
    const parsed = parseFile(file, text);
    const rec = { file, text, ...mod, ...parsed };
    byFile.set(path.resolve(file), rec);
    byNs.set(mod.ns, rec);
  }
  return { byFile, byNs };
}

function resolveBinding(text, bindings, fromFile, ctx, byFile) {
  const head = text.split(".")[0];
  const bound = bindings.get(head);
  if (!bound) return null;
  const resolved = resolveSpec(fromFile, bound.spec, ctx);
  if (!resolved.file) return null;
  const target = byFile.get(path.resolve(resolved.file));
  if (!target) return null;
  const rest = text.split(".").slice(1);
  const exported = bound.namespace ? rest[0] : (rest[0] || bound.exported);
  return { target, exported };
}

function scan(src, prefix, project) {
  const srcRoot = path.resolve(src);
  const ctx = loadConfig(project || srcRoot);
  const { byFile } = fileIndex(srcRoot, prefix || "");
  const classes = [];
  const edges = [];
  const members = [];
  const foreign = new Map();
  const seen = new Set();

  function addEdge(from, to, kind) {
    const key = `${from}|${to}|${kind}`;
    if (!to || from === to || seen.has(key)) return;
    seen.add(key);
    edges.push({ from, to, kind });
  }

  for (const rec of byFile.values()) {
    const stereo = stereotype(rec.flags);
    const cls = { id: rec.id, name: rec.name, ns: rec.ns };
    if (stereo) cls.stereotype = stereo;
    classes.push(cls);
    for (const spec of rec.imports) {
      const resolved = resolveSpec(rec.file, spec, ctx);
      if (resolved.file) {
        const target = byFile.get(path.resolve(resolved.file));
        if (target) addEdge(rec.id, target.id, "dependency");
        else if (!resolved.file.startsWith(srcRoot)) {
          const fid = packageName(spec);
          foreign.set(fid, { id: fid, name: fid, ns: spec, foreign: true });
          addEdge(rec.id, fid, "dependency");
        }
      } else if (resolved.foreign) {
        foreign.set(resolved.foreign, { id: resolved.foreign, name: resolved.foreign, ns: spec, foreign: true });
        addEdge(rec.id, resolved.foreign, "dependency");
      }
    }
    for (const klass of rec.classes) {
      for (const base of klass.bases) {
        const hit = resolveBinding(base.text, rec.bindings, rec.file, ctx, byFile);
        if (!hit) continue;
        const kind = base.kind === "implements" ? "implements" : "inheritance";
        addEdge(rec.id, hit.target.id, kind);
      }
    }
    for (const member of rec.members) {
      members.push({ ...member, ns: rec.ns, file: rec.file });
    }
  }
  classes.push(...foreign.values());
  return { classes, edges, members };
}

function locate(src, prefix, ns, name) {
  const srcRoot = path.resolve(src);
  const { byNs } = fileIndex(srcRoot, prefix || "");
  const rec = byNs.get(ns);
  if (!rec) return { file: null, line: null, title: null };
  let line = null;
  if (name) {
    const member = rec.members.find((m) => m.name === name);
    if (member) line = member.line;
  }
  const title = line == null ? rec.file : `${rec.file}:${line}`;
  return { file: rec.file, line, title };
}

function discover(project) {
  const root = path.resolve(project);
  const srcDir = fs.existsSync(path.join(root, "src")) ? path.join(root, "src") : root;
  const graph = scan(srcDir, "", root);
  const modules = graph.classes.filter((c) => !c.foreign).map((c) => c.ns);
  const order = [];
  const seen = new Set();
  for (const ns of modules.sort()) {
    const seg = ns.split(".")[0];
    if (seg && !seen.has(seg)) {
      seen.add(seg);
      order.push(seg);
    }
  }
  const relSrc = srcDir.endsWith(`${path.sep}src`) ? "src" : ".";
  return { lang: "typescript", src: relSrc, prefix: "", order, title: path.basename(root) };
}

function matchMemberFile(files, memberFile) {
  const target = path.resolve(memberFile);
  if (files[memberFile]) return files[memberFile];
  for (const [key, body] of Object.entries(files)) {
    if (path.resolve(key) === target) return body;
    if (key.endsWith(memberFile) || memberFile.endsWith(key)) return body;
  }
  return null;
}

function istanbulPercent(fileData, start, end) {
  if (!fileData) return null;
  if (fileData.executed_lines || fileData.missing_lines) {
    const executed = new Set(fileData.executed_lines || []);
    const missing = new Set(fileData.missing_lines || []);
    const stmts = [];
    for (let n = start; n <= end; n += 1) {
      if (executed.has(n) || missing.has(n)) stmts.push(n);
    }
    if (!stmts.length) return null;
    return (100 * stmts.filter((n) => executed.has(n)).length) / stmts.length;
  }
  const stmtMap = fileData.statementMap || {};
  const hits = fileData.s || {};
  let covered = 0;
  let total = 0;
  for (const [sid, loc] of Object.entries(stmtMap)) {
    const line = loc && loc.start && loc.start.line;
    if (line == null || line < start || line > end) continue;
    total += 1;
    if (hits[sid]) covered += 1;
  }
  if (!total) return null;
  return (100 * covered) / total;
}

function coverage(src, prefix, coverageFile, project) {
  const graph = scan(src, prefix, project);
  const data = JSON.parse(fs.readFileSync(coverageFile, "utf8"));
  const files = data.files || data;
  const entries = [];
  for (const member of graph.members) {
    const body = matchMemberFile(files, member.file);
    const cov = istanbulPercent(body, member.line, member.endLine);
    const entry = {
      namespace: member.ns,
      name: member.name,
      complexity: member.complexity,
      private: Boolean(member.private),
    };
    if (cov != null) entry.coverage = cov;
    entries.push(entry);
  }
  return { entries };
}

function bucketStatus(status) {
  const key = String(status || "").replace(/[\s_]/g, "").toLowerCase();
  if (["ignored", "compileerror"].includes(key)) return null;
  if (key === "killed" || key === "kill") return "killed";
  if (["survived", "timeout", "suspicious"].includes(key)) return "survived";
  if (["nocoverage", "uncovered", "untested"].includes(key)) return "uncovered";
  return null;
}

function memberAt(members, line) {
  const hits = members.filter((m) => m.line <= line && line <= m.endLine);
  if (!hits.length) return null;
  hits.sort((a, b) => (a.endLine - a.line) - (b.endLine - b.line));
  return hits[0];
}

function mutation(src, prefix, report, project) {
  const data = JSON.parse(fs.readFileSync(report, "utf8"));
  if (data.modules) {
    return {
      modules: data.modules.map((mod) => ({
        namespace: mod.namespace,
        source: mod.source || "",
        forms: (mod.forms || []).map((form) => {
          const killed = Number(form.killed || 0);
          const survived = Number(form.survived || 0);
          const uncovered = Number(form.uncovered || 0);
          return {
            name: form.name,
            private: Boolean(form.private),
            killed,
            survived,
            uncovered,
            sites: Number(form.sites || (killed + survived + uncovered)),
          };
        }),
      })),
    };
  }
  const graph = scan(src, prefix, project);
  const byFile = new Map();
  for (const member of graph.members) byFile.set(path.resolve(member.file), []);
  for (const member of graph.members) byFile.get(path.resolve(member.file)).push(member);
  const counts = new Map();
  const sources = new Map();
  const files = (data.files || {});
  for (const [file, body] of Object.entries(files)) {
    const members = byFile.get(path.resolve(file));
    if (!members) continue;
    for (const mutant of body.mutants || []) {
      const slotName = bucketStatus(mutant.status);
      const line = mutant.location && mutant.location.start && mutant.location.start.line;
      if (!slotName || !line) continue;
      const member = memberAt(members, line);
      if (!member) continue;
      const key = `${member.ns}\0${member.name}`;
      if (!counts.has(key)) {
        counts.set(key, {
          ns: member.ns,
          name: member.name,
          private: Boolean(member.private),
          killed: 0,
          survived: 0,
          uncovered: 0,
        });
        sources.set(member.ns, member.file);
      }
      counts.get(key)[slotName] += 1;
    }
  }
  const grouped = new Map();
  for (const form of counts.values()) {
    form.sites = form.killed + form.survived + form.uncovered;
    if (!grouped.has(form.ns)) grouped.set(form.ns, []);
    grouped.get(form.ns).push({
      name: form.name, private: form.private, killed: form.killed,
      survived: form.survived, uncovered: form.uncovered, sites: form.sites,
    });
  }
  return {
    modules: [...grouped.entries()].sort().map(([namespace, forms]) => ({
      namespace, source: sources.get(namespace) || "", forms,
    })),
  };
}

function arg(argv, name, fallback = "") {
  const i = argv.indexOf(name);
  if (i === -1 || i + 1 >= argv.length) return fallback;
  return argv[i + 1];
}

function main(argv) {
  const cmd = argv[0];
  let payload;
  if (cmd === "scan") payload = scan(arg(argv, "--src"), arg(argv, "--prefix"), arg(argv, "--project"));
  else if (cmd === "locate") payload = locate(arg(argv, "--src"), arg(argv, "--prefix"), arg(argv, "--ns"), arg(argv, "--name"));
  else if (cmd === "discover") payload = discover(arg(argv, "--root"));
  else if (cmd === "coverage") payload = coverage(arg(argv, "--src"), arg(argv, "--prefix"), arg(argv, "--coverage"), arg(argv, "--project"));
  else if (cmd === "mutate") payload = mutation(arg(argv, "--src"), arg(argv, "--prefix"), arg(argv, "--report"), arg(argv, "--project"));
  else {
    process.stderr.write("usage: cli.mjs scan|locate|discover|coverage|mutate\n");
    process.exit(2);
  }
  const text = `${JSON.stringify(payload)}\n`;
  const done = () => process.exit(0);
  // process.exit drops anything still queued on a full pipe. A diagram
  // scan is larger than the 64KB pipe, so wait until the bytes are taken.
  if (process.stdout.write(text)) done();
  else process.stdout.once("drain", done);
}

main(process.argv.slice(2));
