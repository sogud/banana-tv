import * as cheerioCore from 'cheerio/slim';
import * as crypto from 'crypto-es';
import { Template } from '@huggingface/jinja';
import { JSONPath } from 'jsonpath-plus';

const cheerio = {
  ...cheerioCore,
  jp(path, json) { return JSONPath({ path, json, wrap: true, eval: 'safe' }); },
  jinja2(template, variables) { return new Template(String(template)).render(variables); },
};

// Retain the namespaces used by existing source scripts over the maintained ES module API.
const CryptoJS = {
  ...crypto,
  enc: { Hex: crypto.Hex, Latin1: crypto.Latin1, Utf8: crypto.Utf8, Utf16: crypto.Utf16,
    Utf16BE: crypto.Utf16BE, Utf16LE: crypto.Utf16LE, Base64: crypto.Base64, Base64url: crypto.Base64url },
  mode: { CBC: crypto.CBC, CFB: crypto.CFB, CTR: crypto.CTR, CTRGladman: crypto.CTRGladman,
    OFB: crypto.OFB, ECB: crypto.ECB },
  pad: { Pkcs7: crypto.Pkcs7, AnsiX923: crypto.AnsiX923, Iso10126: crypto.Iso10126,
    Iso97971: crypto.Iso97971, ZeroPadding: crypto.ZeroPadding, NoPadding: crypto.NoPadding },
  format: { OpenSSL: crypto.OpenSSLFormatter, Hex: crypto.HexFormatter },
  kdf: { OpenSSL: crypto.OpenSSLKdf },
  lib: { Base: crypto.Base, WordArray: crypto.WordArray, CipherParams: crypto.CipherParams,
    Hasher: crypto.Hasher, Cipher: crypto.Cipher, BlockCipher: crypto.BlockCipher,
    StreamCipher: crypto.StreamCipher, BufferedBlockAlgorithm: crypto.BufferedBlockAlgorithm },
  algo: { MD5: crypto.MD5Algo, SHA1: crypto.SHA1Algo, SHA224: crypto.SHA224Algo,
    SHA256: crypto.SHA256Algo, SHA384: crypto.SHA384Algo, SHA512: crypto.SHA512Algo,
    SHA3: crypto.SHA3Algo, RIPEMD160: crypto.RIPEMD160Algo, HMAC: crypto.HMAC,
    PBKDF2: crypto.PBKDF2Algo, EvpKDF: crypto.EvpKDFAlgo, AES: crypto.AESAlgo,
    DES: crypto.DESAlgo, TripleDES: crypto.TripleDESAlgo, RC4: crypto.RC4Algo,
    RC4Drop: crypto.RC4DropAlgo, Rabbit: crypto.RabbitAlgo, RabbitLegacy: crypto.RabbitLegacyAlgo },
};

export { cheerio, CryptoJS };
export { pdfh, pdfa, pd, urljoin } from './html.js';
export { installStringHelpers } from './strings.js';
