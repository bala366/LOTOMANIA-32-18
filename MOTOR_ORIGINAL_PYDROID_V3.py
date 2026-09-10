# -*- coding: utf-8 -*-
"""
LOTOMANIA V3 — GRUPOS + MÉTRICA DE CONSTRUÇÃO DOS 20 + WALK-FORWARD

Mantém a estrutura aprovada:
    32 escolhidas dentro das 80 que NÃO saíram no último
  + 18 escolhidas dentro das 20 que saíram no último
  = 50 dezenas, deixando exatamente 2 do último de fora.

A V3 estuda COMO O RESULTADO SEGUINTE É CONSTRUÍDO:
- quantas dezenas repetem do concurso anterior;
- quantas novas vêm das 80 falhas;
- grupos fixos do usuário:
    PF = Primo + Fibonacci
    PP = Puro Par
    PI = Puro Ímpar
    OUTROS = dezenas fora desses 3 grupos;
- composição dos 20 sorteados por grupo;
- composição das REPETIDAS por grupo;
- composição das NOVAS (vindas das 80 falhas) por grupo;
- frequência 10/20/50/80/120;
- atraso;
- subida e persistência;
- força de pares nas últimas 80;
- transição: comportamento de cada dezena quando estava no último
  e quando estava fora do último;
- backtest walk-forward sem olhar o futuro;
- escolhe automaticamente o perfil que mais se aproximou de 20.

Também mostra a "AUDITORIA 20":
para cada passo do backtest, quantas das 20 vencedoras eram repetidas e novas,
e se 20 pontos eram estruturalmente possíveis com a regra 18+32.

IMPORTANTE: análise estatística não garante premiação.
"""

import os, re, math, time, itertools, statistics
from collections import Counter, defaultdict

APP = "LOTOMANIA V3 - GRUPOS + MÉTRICA DOS 20"
UNIV = tuple(range(100))
BACKTEST = 260
MIN_HIST = 150
PAIR_WINDOW = 80

PF = {1,3,5,11,17,19,21,23,29,31,37,41,43,55,59,67,73,79,83,97}
PP = {6,12,14,16,20,22,26,28,32,40,44,52,62,64,66,70,80,86,96,98}
PI = {9,15,25,33,51,63,65,69,81,93}
GRUPOS = {"PF":PF, "PURO_PAR":PP, "PURO_IMPAR":PI}
TODOS_GRUPOS = PF | PP | PI
OUTROS = set(UNIV) - TODOS_GRUPOS

# Perfis candidatos. O backtest escolhe, não é fixado no chute.
# freq, atraso, subida, persist, pares, transicao, grupo
PERFIS = {
    "EQUILIBRADO": (1.00,.65,1.00,.90,.75,1.00,1.00),
    "TRANSICAO":   (.80,.45,.80,.75,.55,1.70,1.10),
    "GRUPOS":      (.80,.50,.85,.80,.65,1.00,1.85),
    "SUBIDA":      (.85,.40,1.75,1.20,.65,.90,1.00),
    "PARES80":     (.85,.50,.90,.80,1.65,.95,1.00),
    "HIBRIDO_A":   (1.10,.60,1.25,1.05,1.15,1.35,1.35),
    "HIBRIDO_B":   (.95,.85,1.15,.95,1.30,1.45,1.20),
    "HIBRIDO_C":   (1.20,.45,1.35,1.20,.90,1.25,1.50),
}

def hr(ch="=",n=108): print(ch*n)
def fmt(xs): return " ".join(f"{x:02d}" for x in sorted(xs))

def grupo(n):
    if n in PF: return "PF"
    if n in PP: return "PURO_PAR"
    if n in PI: return "PURO_IMPAR"
    return "OUTROS"

def comp_grupos(nums):
    c=Counter(grupo(n) for n in nums)
    return {g:c[g] for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS")}

def download_dir():
    for p in ("/storage/emulated/0/Download","/storage/emulated/0/Downloads",
              os.path.expanduser("~/Download"),os.path.expanduser("~/Downloads"),"."):
        if os.path.isdir(p): return p
    return "."

def choose_file(folder):
    fs=sorted(f for f in os.listdir(folder) if f.lower().endswith((".txt",".csv")))
    hr(); print("ARQUIVOS DA PASTA DOWNLOAD"); hr()
    if not fs: raise ValueError("Nenhum TXT/CSV na pasta Download.")
    for i,f in enumerate(fs,1): print(f"{i:03d} - {f}")
    while True:
        s=input("Escolha o arquivo pelo número: ").strip()
        if s.isdigit() and 1<=int(s)<=len(fs):
            return os.path.join(folder,fs[int(s)-1])
        print("Número inválido.")

def parse_history(path):
    text=None
    for enc in ("utf-8-sig","utf-8","latin-1"):
        try:
            with open(path,"r",encoding=enc) as f: text=f.read()
            break
        except: pass
    if text is None: raise ValueError("Não consegui abrir o arquivo.")
    rows={}
    for line in text.splitlines():
        vals=[int(x) for x in re.findall(r"\d+",line)]
        if len(vals)<21: continue
        c=vals[0]; ds=[]
        for x in vals[1:]:
            if 0<=x<=99 and x not in ds:
                ds.append(x)
                if len(ds)==20: break
        if c>0 and len(ds)==20: rows[c]=tuple(sorted(ds))
    if not rows: raise ValueError("Não identifiquei concurso + 20 dezenas.")
    return dict(sorted(rows.items()))

def slope(v):
    n=len(v)
    if n<2:return 0.0
    xm=(n-1)/2; ym=sum(v)/n
    den=sum((i-xm)**2 for i in range(n))
    return 0 if den==0 else sum((i-xm)*(x-ym) for i,x in enumerate(v))/den

def mode_range(vals):
    if not vals:return (0,0,0)
    c=Counter(vals); mx=max(c.values())
    modes=sorted(k for k,v in c.items() if v==mx)
    return min(modes), max(modes), statistics.mean(vals)

def estudo_construcao(history):
    items=list(history.items())
    rep_counts=[]; nova_counts=[]
    comp20=[]; comp_rep=[]; comp_nova=[]
    trans=[]
    for i in range(1,len(items)):
        prev=set(items[i-1][1]); cur=set(items[i][1])
        rep=cur&prev; nova=cur-prev
        rep_counts.append(len(rep)); nova_counts.append(len(nova))
        comp20.append(comp_grupos(cur))
        comp_rep.append(comp_grupos(rep))
        comp_nova.append(comp_grupos(nova))
        trans.append((items[i][0],rep,nova))
    return {
        "rep":rep_counts,"nova":nova_counts,
        "comp20":comp20,"comp_rep":comp_rep,"comp_nova":comp_nova,
        "trans":trans
    }

def print_estudo(est):
    hr(); print("COMO OS 20 SORTEADOS SÃO CONSTRUÍDOS"); hr()
    c=Counter(est["rep"]); total=sum(c.values())
    print("REPETIDAS DO CONCURSO ANTERIOR:")
    print(" | ".join(f"{k}={c[k]} ({100*c[k]/total:.2f}%)" for k in sorted(c)))
    print("Moda(s):", [k for k,v in c.items() if v==max(c.values())],
          "| média:",f"{statistics.mean(est['rep']):.2f}")
    print("\nCOMPOSIÇÃO MÉDIA DOS 20 POR GRUPO:")
    for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS"):
        vals=[x[g] for x in est["comp20"]]
        a,b,m=mode_range(vals)
        print(f"{g:11s}: média={m:.2f} | moda/faixa modal={a}-{b} | dist={dict(sorted(Counter(vals).items()))}")
    print("\nCOMPOSIÇÃO MÉDIA DAS REPETIDAS:")
    for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS"):
        vals=[x[g] for x in est["comp_rep"]]
        a,b,m=mode_range(vals)
        print(f"{g:11s}: média={m:.2f} | moda={a}-{b}")
    print("\nCOMPOSIÇÃO MÉDIA DAS NOVAS VINDAS DAS 80 FALHAS:")
    for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS"):
        vals=[x[g] for x in est["comp_nova"]]
        a,b,m=mode_range(vals)
        print(f"{g:11s}: média={m:.2f} | moda={a}-{b}")

def pair_counts(history):
    pc=Counter()
    for _,ds in list(history.items())[-PAIR_WINDOW:]:
        for a,b in itertools.combinations(ds,2):
            pc[(a,b) if a<b else (b,a)]+=1
    return pc

def group_targets(est, recent=80):
    # Aprende alvo de composição principalmente das transições recentes,
    # mas suaviza com histórico completo.
    out={}
    for typ,key in (("rep","comp_rep"),("nova","comp_nova")):
        out[typ]={}
        arr=est[key]
        rr=arr[-min(recent,len(arr)):]
        for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS"):
            allv=[x[g] for x in arr]
            recv=[x[g] for x in rr]
            # alvo = mediana recente + média histórica suavizada
            target=.70*statistics.median(recv)+.30*statistics.mean(allv)
            out[typ][g]=target
    return out

def number_features(history):
    items=list(history.items()); N=len(items)
    occ={n:[] for n in UNIV}
    for i,(_,ds) in enumerate(items):
        for n in ds: occ[n].append(i)

    # taxa de transição empírica:
    # se n está no concurso t, chance de estar em t+1;
    # se n está fora em t, chance de entrar em t+1.
    stay_num=Counter(); stay_den=Counter()
    enter_num=Counter(); enter_den=Counter()
    for i in range(len(items)-1):
        A=set(items[i][1]); B=set(items[i+1][1])
        for n in UNIV:
            if n in A:
                stay_den[n]+=1
                if n in B: stay_num[n]+=1
            else:
                enter_den[n]+=1
                if n in B: enter_num[n]+=1

    pc=pair_counts(history)
    feat={}
    for n in UNIV:
        ar=occ[n]
        f10=sum(i>=N-10 for i in ar)
        f20=sum(i>=N-20 for i in ar)
        f50=sum(i>=N-50 for i in ar)
        f80=sum(i>=N-80 for i in ar)
        f120=sum(i>=N-120 for i in ar)
        atraso=N-1-ar[-1] if ar else N
        serie=[1 if i in ar else 0 for i in range(max(0,N-40),N)]
        sl=slope(serie)
        pers=sum(serie[-20:])/max(1,len(serie[-20:]))
        freq=f10*5.0+f20*2.7+f50*.9+f80*.45+f120*.20
        # atraso moderado; evita premiar cegamente atraso extremo
        atr=max(0,12-abs(atraso-4)*1.4)
        stay=(stay_num[n]+1)/(stay_den[n]+5)   # suavização
        enter=(enter_num[n]+1)/(enter_den[n]+5)
        feat[n]={
            "freq":freq,"atraso":atr,"subida":max(0,sl)*240,
            "persist":pers*38,"stay":stay*100,"enter":enter*100,
            "atraso_raw":atraso
        }
    return feat,pc

def score_base(n,feat,pairavg,pesos,inside_last):
    wf,wa,ws,wp,wpa,wt,wg=pesos
    f=feat[n]
    trans=f["stay"] if inside_last else f["enter"]
    return (wf*f["freq"]+wa*f["atraso"]+ws*f["subida"]+
            wp*f["persist"]+wpa*pairavg+wt*trans), wg

def selecionar_com_alvo(cands, quantidade, base_scores, targets, wg):
    """
    Seleção gulosa que combina força individual com aderência à composição
    PF/Puro Par/Puro Ímpar/Outros aprendida historicamente.
    """
    escolhidos=[]
    counts=Counter()
    restantes=set(cands)
    for pos in range(quantidade):
        best=None
        for n in restantes:
            g=grupo(n)
            # necessidade do grupo: positivo se ainda está abaixo do alvo proporcional
            target=targets[g]
            need=target-counts[g]
            # bônus forte quando o grupo ainda precisa entrar; penaliza excesso
            gb = (need*12.0 if need>0 else need*18.0) * wg
            sc=base_scores[n]+gb
            if best is None or sc>best[0]:
                best=(sc,n)
        n=best[1]
        escolhidos.append(n); restantes.remove(n); counts[grupo(n)]+=1
    return tuple(sorted(escolhidos))

def monta(history,perfil):
    pesos=PERFIS[perfil]
    est=estudo_construcao(history)
    targets=group_targets(est)
    feat,pc=number_features(history)
    ultimo=set(history[max(history)])
    falhas=set(UNIV)-ultimo

    # score das 80 falhas
    sf={}
    for n in falhas:
        pairavg=sum(pc[tuple(sorted((n,m)))] for m in falhas if m!=n)/79.0
        sf[n],wg=score_base(n,feat,pairavg,pesos,False)
    best32=selecionar_com_alvo(falhas,32,sf,targets["nova"],wg)

    # score das 20 do último; escolhe 18 e deixa 2 fora
    su={}
    B=set(best32)
    for n in ultimo:
        pairavg=sum(pc[tuple(sorted((n,m)))] for m in B)/32.0
        su[n],wg=score_base(n,feat,pairavg,pesos,True)
    best18=selecionar_com_alvo(ultimo,18,su,targets["rep"],wg)

    game=tuple(sorted(set(best32)|set(best18)))
    fora=tuple(sorted(ultimo-set(best18)))
    return game,best32,best18,fora,targets

def score_backtest(vals):
    # Primeiro aproxima de 20, depois consistência.
    # 20 recebe peso muito alto, mas sem "vazar" o futuro.
    return (sum(vals)/len(vals)*12 +
            sum(x>=10 for x in vals)*.4 +
            sum(x>=12 for x in vals)*1.2 +
            sum(x>=14 for x in vals)*3.5 +
            sum(x>=15 for x in vals)*5 +
            sum(x>=16 for x in vals)*9 +
            sum(x>=17 for x in vals)*16 +
            sum(x>=18 for x in vals)*30 +
            sum(x>=19 for x in vals)*60 +
            sum(x==20 for x in vals)*150)

def backtest(history):
    items=list(history.items()); N=len(items)
    start=max(MIN_HIST-1,N-BACKTEST-1)
    steps=list(range(start,N-1))
    res={p:[] for p in PERFIS}
    audit={p:[] for p in PERFIS}

    hr(); print("BACKTEST WALK-FORWARD - SEM OLHAR O FUTURO"); hr()
    print(f"Passos testados: {len(steps)} | perfis: {len(PERFIS)}")
    t0=time.time()
    for j,t in enumerate(steps,1):
        hist=dict(items[:t+1])
        prev=set(items[t][1]); alvo=set(items[t+1][1])
        rep=alvo&prev; novas=alvo-prev

        for p in PERFIS:
            game,b32,b18,fora,targets=monta(hist,p)
            G=set(game)
            ac=len(G&alvo)
            ac_rep=len(set(b18)&rep)
            ac_nova=len(set(b32)&novas)
            # 20 é estruturalmente possível se repetidas<=18 e novas<=32
            possivel20=(len(rep)<=18 and len(novas)<=32)
            res[p].append(ac)
            audit[p].append((items[t+1][0],ac,len(rep),len(novas),ac_rep,ac_nova,possivel20))

        if j%5==0 or j==len(steps):
            pct=int(j*100/len(steps))
            sp=j/max(.001,time.time()-t0); eta=int((len(steps)-j)/max(.001,sp))
            best=max((score_backtest(v) for v in res.values() if v),default=0)
            print(f"\r{pct:3d}% | {j}/{len(steps)} | melhor-score={best:.2f} | ETA {eta}s",
                  end="",flush=True)
    print()

    ranking=[]
    for p,v in res.items():
        ranking.append((score_backtest(v),p,statistics.mean(v),max(v),Counter(v)))
    ranking.sort(reverse=True)
    return ranking,res,audit

def dividir_2x16(best32,history,perfil):
    feat,pc=number_features(history); pesos=PERFIS[perfil]
    B=set(best32); vals=[]
    for n in B:
        pa=sum(pc[tuple(sorted((n,m)))] for m in B if m!=n)/31
        sc,_=score_base(n,feat,pa,pesos,False)
        vals.append((sc,n))
    vals.sort(reverse=True)
    a=[];b=[];sa=sb=0.0
    for sc,n in vals:
        if len(a)>=16: b.append(n); sb+=sc
        elif len(b)>=16: a.append(n); sa+=sc
        elif sa<=sb: a.append(n); sa+=sc
        else: b.append(n); sb+=sc
    return tuple(sorted(a)),tuple(sorted(b))

def report(folder,path,history,est,ranking,audit,perfil,game,b32,b18,fora,targets,b1,b2):
    L=[APP,"="*108,
       f"Arquivo: {os.path.basename(path)}",
       f"Concursos: {min(history)} a {max(history)} | total={len(history)}","",
       "GRUPOS FIXOS","-"*108,
       "PF: "+fmt(PF),
       "PURO PAR: "+fmt(PP),
       "PURO ÍMPAR: "+fmt(PI),
       "OUTROS 50: "+fmt(OUTROS),"",
       "CONSTRUÇÃO HISTÓRICA DOS 20","-"*108]

    c=Counter(est["rep"]); total=sum(c.values())
    L.append("Repetidas: "+" | ".join(f"{k}={c[k]} ({100*c[k]/total:.2f}%)" for k in sorted(c)))
    for typ,key in (("20 SORTEADAS","comp20"),("REPETIDAS","comp_rep"),("NOVAS DAS 80","comp_nova")):
        L+=["",typ]
        for g in ("PF","PURO_PAR","PURO_IMPAR","OUTROS"):
            vals=[x[g] for x in est[key]]
            L.append(f"{g}: média={statistics.mean(vals):.3f} | distribuição={dict(sorted(Counter(vals).items()))}")

    L+=["","RANKING DO BACKTEST","-"*108]
    for sc,p,med,mx,cnt in ranking:
        L.append(f"{p:12s} | média={med:.3f} | máximo={mx} | score={sc:.2f} | dist={dict(sorted(cnt.items()))}")

    L+=["",f"PERFIL ESCOLHIDO: {perfil}","-"*108,
        "ALVOS APRENDIDOS DE GRUPO - NOVAS: "+str({k:round(v,2) for k,v in targets["nova"].items()}),
        "ALVOS APRENDIDOS DE GRUPO - REPETIDAS: "+str({k:round(v,2) for k,v in targets["rep"].items()}),
        "32 DAS 80: "+fmt(b32),
        "COMPOSIÇÃO 32: "+str(comp_grupos(b32)),
        "BLOCO 1 - 16: "+fmt(b1),
        "BLOCO 2 - 16: "+fmt(b2),
        "18 DO ÚLTIMO: "+fmt(b18),
        "COMPOSIÇÃO 18: "+str(comp_grupos(b18)),
        "2 DO ÚLTIMO FORA: "+fmt(fora),
        "JOGO FINAL 50: "+fmt(game),
        "COMPOSIÇÃO JOGO 50: "+str(comp_grupos(game))]

    L+=["","AUDITORIA DOS MELHORES PASSOS DO PERFIL VENCEDOR","-"*108]
    aa=sorted(audit[perfil],key=lambda x:x[1],reverse=True)[:40]
    for conc,ac,r,n,acr,acn,poss in aa:
        L.append(f"Concurso {conc}: {ac} pts | resultado construiu {r} repetidas + {n} novas | "
                 f"jogo pegou {acr} repetidas + {acn} novas | 20 estrutural={'SIM' if poss else 'NÃO'}")

    txt=os.path.join(folder,"LOTOMANIA_V3_GRUPOS_METRICA_20_RESULTADO.txt")
    with open(txt,"w",encoding="utf-8") as f:f.write("\n".join(L))

    pdf=None
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.pdfgen import canvas
        from reportlab.lib.colors import green,black
        pdf=os.path.join(folder,"LOTOMANIA_V3_GRUPOS_METRICA_20.pdf")
        c=canvas.Canvas(pdf,pagesize=A4); w,h=A4; y=h-42
        c.setFillColor(green); c.setFont("Helvetica-Bold",15)
        c.drawString(32,y,"LOTOMANIA V3 - GRUPOS + METRICA DOS 20"); y-=25
        c.setFillColor(black); c.setFont("Helvetica-Bold",10)
        c.drawString(32,y,f"Perfil do backtest: {perfil}"); y-=18
        c.setFillColor(green); c.setFont("Helvetica-Bold",8.5)
        c.drawString(32,y,"JOGO 50: "+fmt(game)); y-=20
        c.setFillColor(black); c.setFont("Helvetica",8)
        for line in [
            "32 das 80: "+fmt(b32),
            "Bloco 1: "+fmt(b1),
            "Bloco 2: "+fmt(b2),
            "18 do último: "+fmt(b18),
            "2 fora: "+fmt(fora),
            "Grupos nas 32: "+str(comp_grupos(b32)),
            "Grupos nas 18: "+str(comp_grupos(b18)),
        ]:
            c.drawString(32,y,line[:130]); y-=13
        y-=8
        c.setFont("Helvetica-Bold",9); c.drawString(32,y,"BACKTEST"); y-=14
        c.setFont("Helvetica",8)
        for sc,p,med,mx,cnt in ranking:
            c.drawString(32,y,f"{p}: media={med:.2f} max={mx} score={sc:.1f}"); y-=12
        c.save()
    except Exception:
        pass
    return txt,pdf

def main():
    hr(); print(APP); hr()
    print("V3 estuda os grupos PF / PURO PAR / PURO ÍMPAR e também OUTROS.")
    print("Estuda separadamente as REPETIDAS e as NOVAS que vieram das 80 falhas.")
    print("Depois faz walk-forward e escolhe a métrica que mais se aproximou dos 20.")
    hr()

    folder=download_dir(); path=choose_file(folder); history=parse_history(path)
    print(f"\nConcursos carregados: {len(history)} | primeiro={min(history)} | último={max(history)}")
    print("Último:",fmt(history[max(history)]))

    est=estudo_construcao(history)
    print_estudo(est)

    ranking,res,audit=backtest(history)
    hr(); print("RANKING FINAL DO BACKTEST"); hr()
    for sc,p,med,mx,cnt in ranking:
        print(f"{p:12s} | média={med:.3f} | máximo={mx} | score={sc:.2f}")
    perfil=ranking[0][1]

    game,b32,b18,fora,targets=monta(history,perfil)
    b1,b2=dividir_2x16(b32,history,perfil)

    hr(); print("PALPITE FINAL - LOTOMANIA V3"); hr()
    print("PERFIL ESCOLHIDO:",perfil)
    print("32 DAS 80     :",fmt(b32))
    print("GRUPOS NAS 32 :",comp_grupos(b32))
    print("BLOCO 1 - 16  :",fmt(b1))
    print("BLOCO 2 - 16  :",fmt(b2))
    print("18 DO ÚLTIMO  :",fmt(b18))
    print("GRUPOS NAS 18 :",comp_grupos(b18))
    print("2 FORA        :",fmt(fora))
    print("JOGO DE 50    :",fmt(game))
    print("GRUPOS NO JOGO:",comp_grupos(game))

    txt,pdf=report(folder,path,history,est,ranking,audit,perfil,game,b32,b18,fora,targets,b1,b2)
    hr(); print("TXT salvo em:",txt)
    if pdf: print("PDF salvo em:",pdf)
    else: print("PDF não gerado. Se quiser: pip install reportlab")
    print("\nIMPORTANTE: o backtest mede aderência histórica; não garante 20 pontos.")
    input("\nENTER para encerrar...")

if __name__=="__main__":
    main()
