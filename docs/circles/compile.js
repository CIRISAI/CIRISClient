// Same transform the dc-runtime applies at load (support.js: presets react+typescript),
// wrapped the same way it evaluates the result: new Function("React","module","exports","require", code).
const fs = require('fs');
global.window = global; global.self = global; global.navigator = { userAgent: 'node' };
const Babel = require('./babel.min.js');
const out = [];
for (const name of ['animations-v3.jsx', 'circles-scene.jsx']) {
  const src = fs.readFileSync(name, 'utf8');
  const code = Babel.transform(src, { filename: name, presets: ['react', 'typescript'] }).code;
  out.push(`// ---- ${name} (compiled with @babel/standalone 7.29.0, presets react+typescript; identical to the dc-runtime's load-time transform) ----\n` +
           `(function(React, module, exports, require){\n${code}\n})(window.React, {exports:{}}, {}, function(){ return {}; });`);
}
fs.writeFileSync('circles-explainer.js', out.join('\n\n') + '\n');
console.log('compiled', fs.statSync('circles-explainer.js').size, 'bytes');
