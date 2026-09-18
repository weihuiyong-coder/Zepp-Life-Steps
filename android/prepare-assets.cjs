'use strict';
// Preserve the exact data payload from the existing working desktop implementation.
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('lib/step-client.js', 'utf8');
const match = source.match(/const dataJson = `([\s\S]*?)`;/);
if (!match) throw new Error('Cannot find desktop step template');
const raw = vm.runInNewContext('`' + match[1] + '`', {today: '2000-01-01', steps: 12345}, {timeout: 1000});
const rows = JSON.parse(raw);
if (!Array.isArray(rows) || rows.length !== 1) throw new Error('Unexpected step template structure');
const summary = JSON.parse(rows[0].summary);
if (summary.stp.ttl !== 12345) throw new Error('Desktop step field mismatch');
fs.mkdirSync('android/assets', {recursive: true});
fs.writeFileSync('android/assets/step-template.json', JSON.stringify(rows));
fs.copyFileSync('LICENSE', 'android/assets/LICENSE.txt');
fs.writeFileSync('android/assets/NOTICE.txt', 'Native Android port of weihuiyong-coder/Zepp-Life-Steps. Login adapted from server/python/vendor/zepp_login.py; payload extracted from lib/step-client.js. Original repository license is included. Not an official Zepp or WeChat app.\n');
console.log('Desktop payload extraction and step-field check: PASS');
