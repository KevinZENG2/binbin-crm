export function scaled(value:string,places:number):bigint {
 const match=value.trim().match(new RegExp('^(\\d+)(?:\\.(\\d{1,'+places+'}))?$'));
 if(!match)throw new Error('请填写有效数字，最多 '+places+' 位小数');
 return BigInt(match[1])*10n**BigInt(places)+BigInt((match[2]??'').padEnd(places,'0'));
}
export function cents(value:string):number{const v=scaled(value,2);if(v>100000000000n)throw new Error('单价超过上限');return Number(v);}
export function quoteLineAmount(quantity:string,price:string,discount:string):number {
 const q=scaled(quantity,4),p=BigInt(cents(price)),d=scaled(discount,2);
 if(q<=0n||q>10000000000n||d>10000n)throw new Error('数量须大于零且不超过100万，折扣为0至100%');
 const amount=(q*p*(10000n-d)+50000000n)/100000000n;
 if(amount>100000000000n)throw new Error('单行金额超过上限');return Number(amount);
}
