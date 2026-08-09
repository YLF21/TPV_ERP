const leftOdd = [
  "0001101", "0011001", "0010011", "0111101", "0100011",
  "0110001", "0101111", "0111011", "0110111", "0001011",
];
const leftEven = [
  "0100111", "0110011", "0011011", "0100001", "0011101",
  "0111001", "0000101", "0010001", "0001001", "0010111",
];
const right = [
  "1110010", "1100110", "1101100", "1000010", "1011100",
  "1001110", "1010000", "1000100", "1001000", "1110100",
];
const ean13Parity = [
  "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
  "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
];

export function productLabelEanBits(code: string) {
  if (!/^\d{8}$|^\d{13}$/.test(code)) return "";
  if (code.length === 8) {
    const left = code.slice(0, 4).split("").map((value) => leftOdd[Number(value)]).join("");
    const rightBits = code.slice(4).split("").map((value) => right[Number(value)]).join("");
    return `101${left}01010${rightBits}101`;
  }
  const parity = ean13Parity[Number(code[0])];
  const left = code.slice(1, 7).split("").map((value, index) =>
    parity[index] === "L" ? leftOdd[Number(value)] : leftEven[Number(value)]).join("");
  const rightBits = code.slice(7).split("").map((value) => right[Number(value)]).join("");
  return `101${left}01010${rightBits}101`;
}
