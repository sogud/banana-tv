function strip(value, characters, left, right) {
  if (characters === undefined) {
    if (left && right) return value.trim();
    return left ? value.trimStart() : value.trimEnd();
  }
  const remove = new Set(String(characters));
  const letters = Array.from(value);
  let start = 0;
  let end = letters.length;
  if (left) while (start < end && remove.has(letters[start])) start++;
  if (right) while (end > start && remove.has(letters[end - 1])) end--;
  return letters.slice(start, end).join('');
}

export function installStringHelpers() {
  const methods = {
    contains(search, position) { return String(this).includes(search, position); },
    strip(characters) { return strip(String(this), characters, true, true); },
    lstrip(characters) { return strip(String(this), characters, true, false); },
    rstrip(characters) { return strip(String(this), characters, false, true); },
  };
  for (const [name, implementation] of Object.entries(methods)) {
    if (typeof String.prototype[name] !== 'function') {
      Object.defineProperty(String.prototype, name, { value: implementation, configurable: true, writable: true });
    }
  }
}
