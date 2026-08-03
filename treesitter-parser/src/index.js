#!/usr/bin/env node
/**
 * Tree-sitter FastAPI parser CLI.
 *
 * Protocol:
 *   Input  (stdin): JSON object { "rootPath": "/abs/path/to/repo" }
 *   Output (stdout): JSON object { "apis": [...], "models": {...} }
 *
 * Spawned as a subprocess by the Java backend. One invocation per repo submission.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { parseRepo } = require('./parser');

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const input = JSON.parse(Buffer.concat(chunks).toString('utf8'));

  if (!input.rootPath) {
    process.stderr.write('ERROR: missing rootPath in input\n');
    process.exit(1);
  }

  const rootPath = path.resolve(input.rootPath);
  if (!fs.existsSync(rootPath)) {
    process.stderr.write(`ERROR: rootPath does not exist: ${rootPath}\n`);
    process.exit(1);
  }

  const result = await parseRepo(rootPath);
  process.stdout.write(JSON.stringify(result));
}

main().catch(err => {
  process.stderr.write(`FATAL: ${err.stack || err.message}\n`);
  process.exit(2);
});
