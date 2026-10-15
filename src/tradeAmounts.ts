import {cents} from './commerceAmounts';
export const amountCents=(v:string)=>{const n=cents(v);if(n<=0)throw new Error('金额须大于零');return n;};
export function money(v:unknown):string {if(v===null||v===undefined)return '—';const n=BigInt(String(v)),sign=n<0n?'-':'';const a=n<0n?-n:n;return sign+'¥'+(a/100n).toLocaleString('zh-CN')+'.'+String(a%100n).padStart(2,'0');}
export const decimal=(v:unknown)=>{const n=BigInt(String(v??0));return String(n/100n)+'.'+String(n%100n).padStart(2,'0');};
export function planTotal(plans:{amount:string}[]):number {const total=plans.reduce((n,p)=>n+BigInt(amountCents(p.amount)),0n);if(total>100000000000n)throw new Error('收款计划总额超过上限');return Number(total);}
