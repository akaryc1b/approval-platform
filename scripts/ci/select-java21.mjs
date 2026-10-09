#!/usr/bin/env node
import { appendFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

function absolutePath(value, name) {
  if (typeof value !== 'string' || !path.isAbsolute(value) || /[\r\n\0]/.test(value)) {
    throw new Error(`${name} must be an absolute single-line path`);
  }
  return value;
}

function unsignedDecimal(value) {
  if (value.length === 0) return false;
  for (let index = 0; index < value.length; index += 1) {
    const digit = value.charCodeAt(index);
    if (digit < 48 || digit > 57) return false;
  }
  return true;
}

export function isJava21Version(value) {
  if (typeof value !== 'string') return false;
  const parts = value.split('+');
  if (parts.length > 2 || (parts.length === 2 && !unsignedDecimal(parts[1]))) return false;
  const release = parts[0].split('.');
  return release[0] === '21' && release.slice(1).every(unsignedDecimal);
}

// Select the complete preinstalled runner JDK, never a downloaded runtime or a
// PATH-only java shim. Both tools must report the same Java 21 release first.
export function selectJava21(env = process.env, run = spawnSync) {
  const home = absolutePath(env.JAVA_HOME_21_X64, 'JAVA_HOME_21_X64');
  if (home.includes(path.delimiter)) throw new Error('JAVA_HOME_21_X64 must contain exactly one PATH entry');
  if (home.split(path.sep).some(segment => segment === '.' || segment === '..')) {
    throw new Error('JAVA_HOME_21_X64 must not contain dot path segments');
  }
  const githubEnv = absolutePath(env.GITHUB_ENV, 'GITHUB_ENV');
  const githubPath = absolutePath(env.GITHUB_PATH, 'GITHUB_PATH');
  if (githubEnv === githubPath) throw new Error('GITHUB_ENV and GITHUB_PATH must be distinct');
  const bin = path.join(home, 'bin');
  const versions = ['java', 'javac'].map(tool => {
    const result = run(path.join(bin, tool), ['-version'], {
      encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024,
      env: { ...env, JAVA_HOME: home, PATH: `${bin}${path.delimiter}${env.PATH || ''}` },
    });
    if (result.error || result.status !== 0) throw new Error(`Preinstalled Java 21 ${tool} is unavailable`);
    const output = `${result.stdout || ''}\n${result.stderr || ''}`;
    const version = tool === 'java' ? output.match(/^(?:openjdk|java) version "([^"\r\n]+)"/m)?.[1]
      : output.match(/^javac (\S+)\s*$/m)?.[1];
    if (!isJava21Version(version)) throw new Error(`Preinstalled ${tool} must be Java 21`);
    return version;
  });
  if (versions[0] !== versions[1]) throw new Error('Preinstalled java and javac versions disagree');
  // GitHub applies both files to subsequent steps; publish only after all checks.
  appendFileSync(githubEnv, `JAVA_HOME=${home}\n`);
  appendFileSync(githubPath, `${bin}\n`);
  return { javaHome: home, version: versions[0] };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const selected = selectJava21();
  console.log(`Selected preinstalled JDK ${selected.version}: ${selected.javaHome} (java and javac verified)`);
}
