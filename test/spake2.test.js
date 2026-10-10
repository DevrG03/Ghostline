'use strict';
// Checks public/spake2.js against the test vectors in RFC 9382, Appendix B.
const test = require('node:test');
const assert = require('node:assert');
const Spake2 = require('../public/spake2.js');

const { hexBytes, toBig } = Spake2._internal;
const hex = (b) => Buffer.from(b).toString('hex');
const te = new TextEncoder();

const vectors = [
  {
    A: 'server', B: 'client',
    w: '2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f',
    x: '43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729',
    y: 'dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be',
    pA: '04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c',
    pB: '0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7',
    K: '0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325',
    Ke: '0e0672dc86f8e45565d338b0540abe69',
    KcA: '00c12546835755c86d8c0db7851ae86f',
    KcB: 'a9fa3406c3b781b93d804485430ca27a',
    cA: '58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0',
    cB: 'd3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b',
  },
  {
    A: '', B: 'client',
    w: '0548d8729f730589e579b0475a582c1608138ddf7054b73b5381c7e883e2efae',
    x: '403abbe3b1b4b9ba17e3032849759d723939a27a27b9d921c500edde18ed654b',
    y: '903023b6598908936ea7c929bd761af6039577a9c3f9581064187c3049d87065',
    pA: '04a897b769e681c62ac1c2357319a3d363f610839c4477720d24cbe32f5fd85f44fb92ba966578c1b712be6962498834078262caa5b441ecfa9d4a9485720e918a',
    pB: '04e0f816fd1c35e22065d5556215c097e799390d16661c386e0ecc84593974a61b881a8c82327687d0501862970c64565560cb5671f696048050ca66ca5f8cc7fc',
    K: '048f83ec9f6e4f87cc6f9dc740bdc2769725f923364f01c84148c049a39a735ebda82eac03e00112fd6a5710682767cff5361f7e819e53d8d3c3a2922e0d837aa6',
    Ke: '642f05c473c2cd79909f9a841e2f30a7',
    KcA: 'c6be376fc7cd1301fd0a13adf3e7bffd',
    KcB: 'b7243f4ae60440a49b3f8cab3c1fba07',
    cA: '47d29e6666af1b7dd450d571233085d7a9866e4d49d2645e2df975489521232b',
    cB: '3313c5cefc361d27fb16847a91c2a73b766ffa90a4839122a9b70a2f6bd1d6df',
  },
];

for (const v of vectors) {
  test(`RFC 9382 vector A='${v.A}' B='${v.B}'`, async () => {
    const opts = { idA: te.encode(v.A), idB: te.encode(v.B) };
    const w = toBig(hexBytes(v.w));
    const a = Spake2.start('A', w, { ...opts, scalar: toBig(hexBytes(v.x)) });
    const b = Spake2.start('B', w, { ...opts, scalar: toBig(hexBytes(v.y)) });
    assert.strictEqual(hex(a.message), v.pA);
    assert.strictEqual(hex(b.message), v.pB);
    const ra = await a.finish(b.message);
    const rb = await b.finish(a.message);
    for (const r of [ra, rb]) {
      assert.strictEqual(hex(r._test.K), v.K);
      assert.strictEqual(hex(r._test.Ke), v.Ke);
      assert.strictEqual(hex(r._test.KcA), v.KcA);
      assert.strictEqual(hex(r._test.KcB), v.KcB);
      assert.strictEqual(hex(r._test.cA), v.cA);
      assert.strictEqual(hex(r._test.cB), v.cB);
    }
    assert.ok(ra.verify(rb.confirmation));
    assert.ok(rb.verify(ra.confirmation));
  });
}

test('same code agrees, different code fails', async () => {
  const run = async (codeA, codeB) => {
    const a = Spake2.start('A', await Spake2.passwordScalar(codeA, 17));
    const b = Spake2.start('B', await Spake2.passwordScalar(codeB, 17));
    const ra = await a.finish(b.message);
    const rb = await b.finish(a.message);
    return { ok: ra.verify(rb.confirmation) && rb.verify(ra.confirmation), same: hex(ra.key) === hex(rb.key) };
  };
  assert.deepStrictEqual(await run('482913', '482913'), { ok: true, same: true });
  assert.deepStrictEqual(await run('482913', '482914'), { ok: false, same: false });
});

test('rejects points that are not on the curve', async () => {
  const a = Spake2.start('A', 5n);
  const bad = new Uint8Array(65); bad[0] = 4; bad[64] = 1;
  await assert.rejects(a.finish(bad));
});
