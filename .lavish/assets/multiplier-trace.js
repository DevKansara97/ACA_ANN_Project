(() => {
  const UNIT=1n<<18n, BASE=1n<<25n, MASK=BASE-1n, SCALE=1n<<36n;
  const mod=x=>((x%BASE)+BASE)%BASE;
  function trace(a,b){
    const sa=a<0n?1n:0n,sb=b<0n?1n:0n;
    const na=mod(-a),nb=mod(-b),ma=sa?na:mod(a),mb=sb?nb:mod(b);
    const magnitude=ma*mb,ml=magnitude&MASK,mh=(magnitude>>25n)&MASK;
    const borrow=ml===0n?0n:1n,nl=mod(-ml),nh=mod(-mh-borrow),negative=sa^sb;
    const pl=negative?nl:ml,ph=negative?nh:mh;
    const signedHigh=(ph&(1n<<24n))?ph-BASE:ph;
    return {a,b,sa,sb,na,nb,ma,mb,magnitude,ml,mh,borrow,nl,nh,negative,pl,ph,raw:signedHigh*BASE+pl};
  }
  function assert(test,message){if(!test)throw new Error(message);}
  for(const [a,b] of [[-UNIT,UNIT],[-UNIT,-UNIT],[1n,-1n],[-(1n<<24n),-(1n<<24n)]])assert(trace(a,b).raw===a*b,'Signed multiplication mismatch');
  assert(trace(-1n,1n).borrow===1n,'Borrow must cross chunk boundary');
  assert(trace(-UNIT,UNIT).borrow===0n,'Zero low magnitude must not borrow');
  assert(trace(-(1n<<24n),-(1n<<24n)).ma===(1n<<24n),'Minimum signed input magnitude must remain unsigned');
  if(typeof document==='undefined'){console.log('Trace arithmetic: 4 signed examples plus borrow/minimum checks passed');return;}
  const examples={
    negative:{a:-UNIT,b:UNIT,detail:'Opposite signs select the negative candidate.'},
    'both-negative':{a:-UNIT,b:-UNIT,detail:'Two negative inputs select the positive magnitude.'},
    integers:{a:-3n*UNIT,b:5n*UNIT,detail:'The built-in multiplier computes 3 × 5; XOR selects −15.'},
    fraction:{a:3n*UNIT/2n,b:-2n*UNIT,detail:'The binary point stays at 18 fractional bits in each magnitude; the full product has 36.'},
    borrow:{a:-1n,b:1n,detail:'Inputs are −2⁻¹⁸ and +2⁻¹⁸. The negative product needs a borrow between its two chunks.'},
    minimum:{a:-(1n<<24n),b:-(1n<<24n),detail:'The most-negative inputs become unsigned magnitudes of +64. The exact result is +4096.'}
  };
  const hex=x=>mod(x).toString(16).toUpperCase().padStart(7,'0');
  const decimal=(raw,scale)=>{
    const n=Number(raw)/Number(scale);
    if(n!==0&&Math.abs(n)<0.000001)return n.toExponential(11);
    return new Intl.NumberFormat('en',{maximumFractionDigits:12}).format(n).replace(/-/g,'−');
  };
  const $=id=>document.getElementById(id);
  const put=(id,value)=>{const el=$(id);assert(el,'Missing element '+id);el.textContent=value;};
  const descriptions={A:'Original signed input bit pattern',B:'Original signed input bit pattern',SIGN_A:'Original A sign bit',SIGN_B:'Original B sign bit',NEG_A:'25-bit negation candidate for A',NEG_B:'25-bit negation candidate for B',MAG_A:'Unsigned magnitude selected by A sign',MAG_B:'Unsigned magnitude selected by B sign',NEGATIVE:'XOR of original sign bits',MAG_HI:'Unsigned magnitude bits 49:25',MAG_LO:'Unsigned magnitude bits 24:0',BORROW:'Low subtractor borrow into high subtractor',NEG_HI:'High chunk of full negative candidate',NEG_LO:'Low chunk of full negative candidate',P_HI:'Selected signed upper chunk',P_LO:'Selected unsigned lower chunk'};
  function render(key){
    const e=examples[key],t=trace(e.a,e.b);
    put('input-summary',decimal(e.a,UNIT)+' × '+decimal(e.b,UNIT));
    put('example-detail',e.detail);put('result',decimal(t.raw,SCALE));
    put('result-word','P_HI '+hex(t.ph)+' · P_LO '+hex(t.pl));
    const values={'svg-a':hex(t.a),'svg-b':hex(t.b),'svg-signs':'SIGN_A='+t.sa+' · SIGN_B='+t.sb,'svg-negative':'NEGATIVE = '+t.negative,'svg-maga':'MAG_A '+hex(t.ma),'svg-magb':'MAG_B '+hex(t.mb),'svg-maghi':'HI '+hex(t.mh),'svg-maglo':'LO '+hex(t.ml),'svg-borrow':'BORROW = '+t.borrow,'svg-neghi':'NEG_HI '+hex(t.nh),'svg-selection':t.negative?'Select NEG_HI / NEG_LO':'Select MAG_HI / MAG_LO','svg-phi':'P_HI '+hex(t.ph),'svg-plo':'P_LO '+hex(t.pl),'mobile-inputs':'A '+hex(t.a)+' · B '+hex(t.b),'mobile-mags':hex(t.ma)+' × '+hex(t.mb),'mobile-sign':'SIGN_A '+t.sa+', SIGN_B '+t.sb+', XOR '+t.negative,'mobile-product':'HI '+hex(t.mh)+' · LO '+hex(t.ml),'mobile-negative':'BORROW '+t.borrow+' · HI '+hex(t.nh)+' · LO '+hex(t.nl),'mobile-result':'P_HI '+hex(t.ph)+' · P_LO '+hex(t.pl)};
    Object.entries(values).forEach(([id,value])=>put(id,value));
    const nets={A:t.a,B:t.b,SIGN_A:t.sa,SIGN_B:t.sb,NEG_A:t.na,NEG_B:t.nb,MAG_A:t.ma,MAG_B:t.mb,NEGATIVE:t.negative,MAG_HI:t.mh,MAG_LO:t.ml,BORROW:t.borrow,NEG_HI:t.nh,NEG_LO:t.nl,P_HI:t.ph,P_LO:t.pl};
    const body=$('trace-body');body.replaceChildren();
    Object.entries(nets).forEach(([name,value])=>{
      const row=document.createElement('tr');row.id='signal-'+name.toLowerCase();
      [name,['SIGN_A','SIGN_B','NEGATIVE','BORROW'].includes(name)?value.toString():hex(value),descriptions[name]].forEach((v,i)=>{const cell=document.createElement('td');cell.textContent=v;if(i<2)cell.className='mono';row.append(cell);});body.append(row);
    });
    document.querySelectorAll('[data-case]').forEach(button=>{const selected=button.dataset.case===key;button.classList.toggle('btn-primary',selected);button.setAttribute('aria-pressed',String(selected));});
    put('selection-note',t.negative?'XOR = 1, so both output MUXes choose the negative candidate. The sign is restored across all 50 bits, not by setting only the MSB.':'XOR = 0, so both output MUXes choose the positive magnitude. The subtractors still calculate a negative candidate, but it is not selected.');
  }
  document.querySelectorAll('[data-case]').forEach(button=>button.addEventListener('click',()=>render(button.dataset.case)));
  $('theme-toggle').addEventListener('click',()=>{const dark=document.documentElement.dataset.theme!=='night';document.documentElement.dataset.theme=dark?'night':'light';$('theme-toggle').textContent=dark?'Light theme':'Dark theme';});
  const inventory=[['Pin',4,'Two inputs and two output chunks'],['Splitter',2,'Extract the original sign bits'],['Negator',2,'Prepare negated input candidates'],['Multiplexer',4,'Two choose magnitudes; two choose final sign'],['XOR Gate',1,'Determine whether input signs differ'],['Multiplier',1,'Multiply the unsigned magnitudes once'],['Subtractor',2,'Negate the full product with linked borrow'],['Constant',2,'One 25-bit zero and one one-bit zero'],['Tunnel',47,'Named wire connections only'],['Text',7,'Explanatory labels; no electrical operation']];
  inventory.forEach(([name,count,purpose])=>{const row=document.createElement('tr');row.id='component-'+name.toLowerCase().replaceAll(' ','-');[name,count,purpose].forEach(value=>{const td=document.createElement('td');td.textContent=String(value);row.append(td);});$('inventory-body').append(row);});
  render('negative');
})();
