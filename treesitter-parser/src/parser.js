'use strict';

const fs = require('fs');
const path = require('path');
const Parser = require('tree-sitter');
const Python = require('tree-sitter-python');

const KNOWN_PRIMITIVES = new Set([
  'int', 'str', 'float', 'bool', 'bytes', 'None', 'Any', 'Dict', 'List', 'Tuple',
  'Optional', 'Union', 'Type', 'UUID', 'UUID4', 'datetime', 'date', 'time',
  'Decimal', 'condecimal', 'PositiveInt', 'PositiveFloat', 'constr',
  'EmailStr', 'HttpUrl', 'AnyUrl', 'SecretStr',
  'Response', 'JSONResponse', 'StreamingResponse', 'FileResponse',
  'HTMLResponse', 'RedirectResponse', 'PlainTextResponse', 'BackgroundTasks',
  'Request', 'HTTPException',
]);

const HTTP_METHODS = new Set(['get', 'post', 'put', 'delete', 'patch', 'options', 'head']);

/**
 * Parse an entire repo and return the extracted API structure.
 */
async function parseRepo(rootPath) {
  const parser = new Parser();
  parser.setLanguage(Python);

  const pyFiles = collectPyFiles(rootPath);
  const trees = new Map(); // relPath → { tree, source }

  // Phase 1: Parse all Python files
  for (const absPath of pyFiles) {
    const source = fs.readFileSync(absPath, 'utf8');
    const tree = parser.parse(source);
    const relPath = path.relative(rootPath, absPath).replace(/\\/g, '/');
    trees.set(relPath, { tree, source, absPath });
  }

  // Phase 2: Build model index (class name → relPath)
  const modelIndex = buildModelIndex(trees);

  // Phase 3: Build router prefix map from include_router calls
  const externalPrefixMap = buildExternalPrefixMap(trees);

  // Phase 4: Extract routes from each file
  const apis = [];
  for (const [relPath, { tree, source }] of trees) {
    const fileApis = extractRoutes(tree, source, relPath, modelIndex, externalPrefixMap, trees);
    apis.push(...fileApis);
  }

  return { apis, models: Object.fromEntries(modelIndex) };
}

// ─── File collection ───────────────────────────────────────────

function collectPyFiles(rootPath) {
  const result = [];
  const walk = (dir) => {
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); }
    catch { return; }
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        if (ent.name === 'node_modules' || ent.name === '.git' || ent.name === '__pycache__' || ent.name === '.venv' || ent.name === 'venv') continue;
        walk(full);
      } else if (ent.name.endsWith('.py') && !ent.name.startsWith('test_') && !full.includes('/tests/') && !full.includes('\\tests\\')) {
        result.push(full);
      }
    }
  };
  walk(rootPath);
  return result;
}

// ─── Phase 2: Model index ──────────────────────────────────────

function buildModelIndex(trees) {
  const index = new Map(); // className → { relPath, fields: [...] }
  for (const [relPath, { tree, source }] of trees) {
    const rootNode = tree.rootNode;
    for (const node of findAll(rootNode, 'class_definition')) {
      const nameNode = node.childForFieldName('name');
      if (!nameNode) continue;
      const className = nameNode.text;
      if (index.has(className)) continue;

      const fields = extractClassFields(node, source);
      index.set(className, { relPath, fields });
    }
  }
  return index;
}

function extractClassFields(classNode, source) {
  const fields = [];
  const body = classNode.childForFieldName('body');
  if (!body) return fields;

  for (const stmt of body.namedChildren) {
    // type_alias_statement or expression_statement with type annotation
    if (stmt.type === 'expression_statement') {
      const child = stmt.namedChildren[0];
      if (child && child.type === 'assignment') {
        const annNode = child.childForFieldName('type');
        if (annNode) {
          const name = child.childForFieldName('left')?.text;
          const type = annNode.text;
          if (name) fields.push({ name, type: cleanType(type) });
        }
      }
    }
    // Annotated assignment: name: Type or name: Type = default
    if (stmt.type === 'typed_statement' || stmt.type === 'expression_statement') {
      // Try finding a type annotation node pattern
    }
    // The actual node type for `name: str` in tree-sitter-python is 'type' (a child of an annotated assignment)
    if (stmt.type.includes('assignment') || stmt.type === 'expression_statement') {
      // Already handled above
    }
  }

  // More reliable: walk the body for all nodes that look like annotations
  for (const child of body.namedChildren) {
    if (child.type === 'expression_statement') {
      const inner = child.firstNamedChild;
      if (inner && inner.type === 'assignment') {
        // x: int = 5 shows as assignment with type annotation
        const targets = inner.childForFieldName('left');
        const typeNode = inner.childForFieldName('type');
        if (targets && typeNode) {
          fields.push({ name: targets.text, type: cleanType(typeNode.text) });
        }
      } else if (inner && inner.type === 'type') {
        // fallback
      }
    }
  }

  // Deduplicate
  const seen = new Set();
  return fields.filter(f => {
    if (seen.has(f.name)) return false;
    seen.add(f.name);
    return true;
  });
}

// ─── Phase 3: External prefix map ─────────────────────────────

function buildExternalPrefixMap(trees) {
  const map = new Map(); // routerRef → prefix
  for (const [relPath, { tree, source }] of trees) {
    const rootNode = tree.rootNode;
    for (const node of findAll(rootNode, 'call')) {
      const funcNode = node.childForFieldName('function');
      if (!funcNode || funcNode.type !== 'attribute') continue;
      const methodName = funcNode.childForFieldName('attribute')?.text;
      if (methodName !== 'include_router') continue;

      const args = node.childForFieldName('arguments');
      if (!args) continue;

      let routerRef = null;
      let prefix = null;

      for (const arg of args.namedChildren) {
        if (arg.type === 'identifier' && !routerRef) {
          routerRef = arg.text;
        } else if (arg.type === 'attribute' && !routerRef) {
          routerRef = arg.text;
        } else if (arg.type === 'keyword_argument') {
          const kwName = arg.childForFieldName('name')?.text;
          const kwValue = arg.childForFieldName('value');
          if (kwName === 'prefix' && kwValue) {
            prefix = stripQuotes(kwValue.text);
          }
        }
      }

      if (routerRef && prefix !== null) {
        map.set(routerRef, prefix);
      }
    }
  }
  return map;
}

// ─── Phase 4: Route extraction ─────────────────────────────────

function extractRoutes(tree, source, relPath, modelIndex, externalPrefixMap, trees) {
  const apis = [];
  const rootNode = tree.rootNode;

  // Get local router info
  const localPrefix = findLocalRouterPrefix(rootNode);
  const routerTags = findRouterTags(rootNode);
  const externalPrefix = resolveExternalPrefix(relPath, externalPrefixMap);
  const pathPrefix = normalizePathPrefix(externalPrefix + localPrefix);

  // Find decorated definitions
  for (const node of findAll(rootNode, 'decorated_definition')) {
    const route = extractSingleRoute(node, source, relPath, pathPrefix, routerTags, modelIndex, trees);
    if (route) apis.push(route);
  }

  return apis;
}

function extractSingleRoute(decoratedNode, source, relPath, pathPrefix, routerTags, modelIndex, trees) {
  // Get the decorator(s)
  const decorators = decoratedNode.namedChildren.filter(c => c.type === 'decorator');
  const definition = decoratedNode.childForFieldName('definition');
  if (!definition || definition.type !== 'function_definition') return null;

  // Find the HTTP method decorator
  let httpMethod = null;
  let decoratorCall = null;

  for (const dec of decorators) {
    const callNode = dec.namedChildren.find(c => c.type === 'call');
    if (!callNode) continue;

    const funcNode = callNode.childForFieldName('function');
    if (!funcNode || funcNode.type !== 'attribute') continue;

    const method = funcNode.childForFieldName('attribute')?.text;
    if (method && HTTP_METHODS.has(method)) {
      httpMethod = method.toUpperCase();
      decoratorCall = callNode;
      break;
    }
  }

  if (!httpMethod || !decoratorCall) return null;

  // Extract path from first positional string argument
  const args = decoratorCall.childForFieldName('arguments');
  let routePath = '';
  let statusCode = null;
  let responseModel = null;
  let routeTags = [];

  if (args) {
    // First positional string argument is the path
    for (const arg of args.namedChildren) {
      if (arg.type === 'string' && routePath === '') {
        routePath = stripQuotes(arg.text);
      } else if (arg.type === 'keyword_argument') {
        const kwName = arg.childForFieldName('name')?.text;
        const kwValue = arg.childForFieldName('value');
        if (kwName === 'status_code' && kwValue) {
          const code = parseInt(kwValue.text, 10);
          if (!isNaN(code)) statusCode = code;
        } else if (kwName === 'response_model' && kwValue) {
          responseModel = kwValue.text;
        } else if (kwName === 'tags' && kwValue) {
          routeTags = extractListStrings(kwValue);
        }
      }
    }
  }

  // Validate path: if non-empty must start with /
  if (routePath && !routePath.startsWith('/')) return null;

  const fullPath = routePath ? pathPrefix + routePath : (pathPrefix || '/');

  // Handler info
  const handlerName = definition.childForFieldName('name')?.text;
  if (!handlerName) return null;

  const params = definition.childForFieldName('parameters');
  const returnType = definition.childForFieldName('return_type')?.text || null;

  // Extract docstring
  const body = definition.childForFieldName('body');
  const description = extractDocstring(body);

  // Parse parameters
  const parsedParams = parseParameters(params, fullPath, modelIndex);

  // Determine request body
  let requestBodyType = null;
  let requestBodyFields = null;
  for (const p of parsedParams) {
    if (p.location === 'BODY') {
      requestBodyType = p.type;
      const model = modelIndex.get(p.type);
      if (model) requestBodyFields = model.fields;
      break;
    }
  }

  // Determine response body type
  let responseBodyType = responseModel || cleanReturnType(returnType);
  let responseBodyFields = null;
  if (responseBodyType && !KNOWN_PRIMITIVES.has(responseBodyType)) {
    const model = modelIndex.get(responseBodyType);
    if (model) responseBodyFields = model.fields;
  } else if (responseBodyType && KNOWN_PRIMITIVES.has(responseBodyType)) {
    responseBodyType = null;
  }

  // Build the API object
  const api = {
    method: httpMethod,
    path: fullPath,
    controller: deriveController(relPath),
    handler: handlerName,
    description: description || null,
    tags: routeTags.length > 0 ? routeTags : (routerTags.length > 0 ? routerTags : null),
    parameters: parsedParams.length > 0 ? parsedParams : null,
    requestBodyType: requestBodyType || null,
    requestBodyFields: requestBodyFields || null,
    responseBodyType: responseBodyType || null,
    responseBodyFields: responseBodyFields || null,
    statusCodes: statusCode ? [statusCode] : null,
    sourceFile: relPath,
    sourceLine: decoratedNode.startPosition.row + 1,
  };

  // Ensure path params present
  ensurePathParamsPresent(api);
  return api;
}

// ─── Parameter parsing ─────────────────────────────────────────

function parseParameters(paramsNode, urlPath, modelIndex) {
  if (!paramsNode) return [];

  const pathParams = new Set();
  const matches = urlPath.matchAll(/\{(\w+)\}/g);
  for (const m of matches) pathParams.add(m[1]);

  const result = [];
  const skipNames = new Set(['self', 'db', 'session', 'background_tasks']);

  for (const param of paramsNode.namedChildren) {
    if (param.type === 'identifier') {
      // Positional param without type annotation — skip unless it's a path param
      const name = param.text;
      if (skipNames.has(name) || name === '*') continue;
      if (pathParams.has(name)) {
        result.push({ name, type: 'string', location: 'PATH', required: true });
      }
      continue;
    }

    if (param.type === 'typed_parameter' || param.type === 'default_parameter' || param.type === 'typed_default_parameter') {
      const info = extractParamInfo(param);
      if (!info || skipNames.has(info.name)) continue;

      const cleanedType = cleanType(info.typeAnnotation || 'string');
      // Body(...) / Query(...) with Ellipsis means required despite having a "default"
      const isEllipsisDefault = info.defaultText && info.defaultText.includes('...');
      const required = isEllipsisDefault || (!info.hasDefault && !(info.typeAnnotation || '').includes('Optional'));

      let location;
      if (info.defaultText && info.defaultText.startsWith('Header(')) {
        location = 'HEADER';
      } else if (info.defaultText && info.defaultText.startsWith('Body(')) {
        location = 'BODY';
      } else if (info.defaultText && info.defaultText.startsWith('Depends(')) {
        // Depends — skip for now (cross-file resolution)
        continue;
      } else if (pathParams.has(info.name)) {
        location = 'PATH';
      } else if (!KNOWN_PRIMITIVES.has(cleanedType) && modelIndex.has(cleanedType)) {
        location = 'BODY';
      } else if (isPrimitive(cleanedType)) {
        location = 'QUERY';
      } else {
        continue;
      }

      result.push({ name: info.name, type: cleanedType, location, required });
    }
  }

  return result;
}

function extractParamInfo(paramNode) {
  // Handle different parameter node types
  if (paramNode.type === 'identifier') {
    return { name: paramNode.text, typeAnnotation: null, hasDefault: false, defaultText: null };
  }

  let name = null;
  let typeAnnotation = null;
  let hasDefault = false;
  let defaultText = null;

  // typed_parameter: name: Type
  // default_parameter: name = default
  // typed_default_parameter: name: Type = default
  const nameNode = paramNode.childForFieldName('name');
  if (nameNode) name = nameNode.text;
  else {
    // fallback: first identifier child
    const idChild = paramNode.namedChildren.find(c => c.type === 'identifier');
    if (idChild) name = idChild.text;
  }

  const typeNode = paramNode.childForFieldName('type');
  if (typeNode) typeAnnotation = typeNode.text;

  const valueNode = paramNode.childForFieldName('value');
  if (valueNode) {
    hasDefault = true;
    defaultText = valueNode.text;
  }

  if (!name) return null;
  return { name, typeAnnotation, hasDefault, defaultText };
}

// ─── Router prefix helpers ─────────────────────────────────────

function findLocalRouterPrefix(rootNode) {
  for (const node of findAll(rootNode, 'assignment')) {
    const right = node.childForFieldName('right');
    if (!right || right.type !== 'call') continue;
    const funcNode = right.childForFieldName('function');
    if (!funcNode || funcNode.text !== 'APIRouter') continue;

    const args = right.childForFieldName('arguments');
    if (!args) continue;

    for (const arg of args.namedChildren) {
      if (arg.type === 'keyword_argument') {
        const kwName = arg.childForFieldName('name')?.text;
        const kwValue = arg.childForFieldName('value');
        if (kwName === 'prefix' && kwValue) {
          return stripQuotes(kwValue.text);
        }
      }
    }
  }
  return '';
}

function findRouterTags(rootNode) {
  for (const node of findAll(rootNode, 'assignment')) {
    const right = node.childForFieldName('right');
    if (!right || right.type !== 'call') continue;
    const funcNode = right.childForFieldName('function');
    if (!funcNode || funcNode.text !== 'APIRouter') continue;

    const args = right.childForFieldName('arguments');
    if (!args) continue;

    for (const arg of args.namedChildren) {
      if (arg.type === 'keyword_argument') {
        const kwName = arg.childForFieldName('name')?.text;
        const kwValue = arg.childForFieldName('value');
        if (kwName === 'tags' && kwValue) {
          return extractListStrings(kwValue);
        }
      }
    }
  }
  return [];
}

function resolveExternalPrefix(relPath, externalPrefixMap) {
  const mod = path.basename(relPath, '.py');
  for (const [ref, prefix] of externalPrefixMap) {
    if (ref === mod || ref.startsWith(mod + '.') || ref.endsWith('.' + mod)) {
      return prefix;
    }
  }
  return '';
}

function normalizePathPrefix(prefix) {
  if (!prefix) return '';
  let s = prefix.startsWith('/') ? prefix : '/' + prefix;
  if (s.endsWith('/')) s = s.slice(0, -1);
  return s;
}

// ─── Utility helpers ───────────────────────────────────────────

function findAll(node, type) {
  const results = [];
  const cursor = node.walk();
  let reachedRoot = false;

  while (true) {
    if (cursor.nodeType === type) {
      results.push(cursor.currentNode);
    }
    if (cursor.gotoFirstChild()) continue;
    if (cursor.gotoNextSibling()) continue;
    while (true) {
      if (!cursor.gotoParent()) { reachedRoot = true; break; }
      if (cursor.gotoNextSibling()) break;
    }
    if (reachedRoot) break;
  }

  return results;
}

function stripQuotes(s) {
  if (!s) return '';
  // Handle f-strings, b-strings etc.
  let t = s;
  if (/^[fFbBrRuU]+['"]/.test(t)) {
    t = t.replace(/^[fFbBrRuU]+/, '');
  }
  if ((t.startsWith('"""') && t.endsWith('"""')) || (t.startsWith("'''") && t.endsWith("'''"))) {
    return t.slice(3, -3);
  }
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1);
  }
  return s;
}

function extractListStrings(node) {
  const strings = [];
  if (node.type === 'list') {
    for (const child of node.namedChildren) {
      if (child.type === 'string') {
        strings.push(stripQuotes(child.text));
      }
    }
  }
  return strings;
}

function cleanType(t) {
  if (!t) return 'string';
  const optMatch = t.match(/^Optional\[(.+)\]$/);
  if (optMatch) return cleanType(optMatch[1]);
  const listMatch = t.match(/^List\[(.+)\]$/);
  if (listMatch) return cleanType(listMatch[1]);
  return t.trim();
}

function cleanReturnType(t) {
  if (!t) return null;
  const cleaned = cleanType(t);
  if (KNOWN_PRIMITIVES.has(cleaned) || cleaned === 'None') return null;
  return cleaned;
}

function isPrimitive(t) {
  return KNOWN_PRIMITIVES.has(t) || t.startsWith('List[') || t.startsWith('Optional[') || t.startsWith('Dict[');
}

function deriveController(relPath) {
  const base = path.basename(relPath, '.py');
  return base.split(/[-_.]/)
    .filter(w => w.length > 0)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join('');
}

function extractDocstring(bodyNode) {
  if (!bodyNode || bodyNode.namedChildCount === 0) return null;
  const first = bodyNode.namedChildren[0];
  if (first.type === 'expression_statement') {
    const inner = first.firstNamedChild;
    if (inner && inner.type === 'string') {
      const text = stripQuotes(inner.text).trim();
      // Return first line of the docstring
      const firstLine = text.split('\n')[0].trim();
      return firstLine || null;
    }
  }
  return null;
}

function ensurePathParamsPresent(api) {
  if (!api.path) return;
  const declared = new Set();
  if (api.parameters) api.parameters.forEach(p => declared.add(p.name));

  const toAdd = [];
  const matches = api.path.matchAll(/\{(\w+)\}/g);
  for (const m of matches) {
    const name = m[1];
    if (!declared.has(name)) {
      declared.add(name);
      toAdd.push({ name, location: 'PATH', required: true, type: 'string' });
    }
  }

  if (toAdd.length > 0) {
    api.parameters = [...toAdd, ...(api.parameters || [])];
  }
}

module.exports = { parseRepo };
