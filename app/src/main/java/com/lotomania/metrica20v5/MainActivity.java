package com.lotomania.metrica20v5;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {

    TextView out;
    ProgressBar bar;
    Button open, generate, pdfButton;
    TreeMap<Integer,Set<Integer>> loadedHistory;
    Result lastResult;
    String lastReport;
    final int PICK=10;
    Uri chosen;

    static final int[] PF={1,3,5,11,17,19,21,23,29,31,37,41,43,55,59,67,73,79,83,97};
    static final int[] PP={6,12,14,16,20,22,26,28,32,40,44,52,62,64,66,70,80,86,96,98};
    static final int[] PI={9,15,25,33,51,63,65,69,81,93};

    static final String[] NAMES={
            "EQUILIBRADO","TRANSICAO","GRUPOS","SUBIDA",
            "PARES80","HIBRIDO_A","HIBRIDO_B","HIBRIDO_C"
    };

    // freq, atraso, subida, persist, pares, transicao, grupo
    static final double[][] W={
            {1.00,.65,1.00,.90,.75,1.00,1.00},
            {.80,.45,.80,.75,.55,1.70,1.10},
            {.80,.50,.85,.80,.65,1.00,1.85},
            {.85,.40,1.75,1.20,.65,.90,1.00},
            {.85,.50,.90,.80,1.65,.95,1.00},
            {1.10,.60,1.25,1.05,1.15,1.35,1.35},
            {.95,.85,1.15,.95,1.30,1.45,1.20},
            {1.20,.45,1.35,1.20,.90,1.25,1.50}
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,18,18,18);
        root.setBackgroundColor(Color.rgb(245,124,0));

        TextView logo=new TextView(this);
        logo.setText("☘\nLOTOMANIA V5\nMÉTRICA DOS 20");
        logo.setGravity(Gravity.CENTER);
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(28);
        logo.setPadding(0,16,0,18);
        root.addView(logo);

        TextView sub=new TextView(this);
        sub.setText("Mesmo motor V4 • novo painel • volante em verde");
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(14);
        sub.setPadding(0,0,0,14);
        root.addView(sub);

        open=new Button(this);
        open.setText("1. CARREGAR CONCURSOS TXT/CSV");
        root.addView(open);

        generate=new Button(this);
        generate.setText("2. GERAR JOGO");
        generate.setEnabled(false);
        root.addView(generate);

        pdfButton=new Button(this);
        pdfButton.setText("3. GERAR PDF DO VOLANTE");
        pdfButton.setEnabled(false);
        root.addView(pdfButton);

        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        root.addView(bar);

        out=new TextView(this);
        out.setText("Carregue a base de concursos para começar.\n");
        out.setTextColor(Color.BLACK);
        out.setTextSize(14);
        out.setPadding(16,16,16,16);
        out.setBackgroundColor(Color.WHITE);

        ScrollView sv=new ScrollView(this);
        sv.addView(out);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));

        setContentView(root);
        open.setOnClickListener(v->pick());
        generate.setOnClickListener(v->runEngine());
        pdfButton.setOnClickListener(v->{
            if(lastResult==null || lastReport==null)return;
            new Thread(()->{
                try{ String path=savePdf(lastReport,lastResult); say("PDF do volante salvo: "+path); }
                catch(Exception e){ say("ERRO PDF: "+e.getMessage()); }
            }).start();
        });
    }

    void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("text/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,PICK);
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==PICK&&c==RESULT_OK&&d!=null){
            chosen=d.getData();
            try{
                loadedHistory=parse();
                out.setText("BASE CARREGADA COM SUCESSO\n");
                say("Concursos: "+loadedHistory.size()+" | primeiro="+loadedHistory.firstKey()+" | último="+loadedHistory.lastKey());
                say("Último: "+fmt(loadedHistory.lastEntry().getValue()));
                say("\nAgora toque em 2. GERAR JOGO.");
                generate.setEnabled(true);
                pdfButton.setEnabled(false);
                lastResult=null; lastReport=null;
            }catch(Exception e){
                out.setText("ERRO AO CARREGAR: "+e.getMessage()+"\n");
                generate.setEnabled(false);
            }
        }
    }

    void say(String s){ runOnUiThread(()->out.append(s+"\n")); }
    void prog(int p){ runOnUiThread(()->bar.setProgress(p)); }

    static String fmt(Collection<Integer>x){
        ArrayList<Integer>a=new ArrayList<>(x);
        Collections.sort(a);
        StringBuilder s=new StringBuilder();
        for(int n:a)s.append(String.format(Locale.US,"%02d ",n));
        return s.toString().trim();
    }

    static int grp(int n){
        for(int x:PF)if(x==n)return 0;
        for(int x:PP)if(x==n)return 1;
        for(int x:PI)if(x==n)return 2;
        return 3;
    }

    static String grpName(int g){
        return g==0?"PF":g==1?"PURO_PAR":g==2?"PURO_IMPAR":"OUTROS";
    }

    void runEngine(){
        if(loadedHistory==null){ say("Carregue os concursos primeiro."); return; }
        open.setEnabled(false);
        generate.setEnabled(false);
        pdfButton.setEnabled(false);
        out.setText("");
        prog(0);

        new Thread(()->{
            try{
                TreeMap<Integer,Set<Integer>> h=new TreeMap<>(loadedHistory);
                if(h.size()<151) throw new Exception("Base pequena: precisa de pelo menos 151 concursos.");

                say("Concursos carregados: "+h.size()+" | primeiro="+h.firstKey()+" | último="+h.lastKey());
                say("Último: "+fmt(h.lastEntry().getValue()));

                // Métrica histórica de repetição.
                int[] dist=new int[21];
                List<Set<Integer>> draws=new ArrayList<>(h.values());
                for(int i=1;i<draws.size();i++){
                    Set<Integer>x=new HashSet<>(draws.get(i));
                    x.retainAll(draws.get(i-1));
                    dist[x.size()]++;
                }

                say("\nMÉTRICA HISTÓRICA DE REPETIÇÃO");
                for(int i=0;i<=20;i++) if(dist[i]>0) say(i+" repetidas = "+dist[i]);

                // Composição histórica dos grupos nos resultados de 20.
                long[] gc=new long[4];
                for(Set<Integer>d:draws) for(int n:d) gc[grp(n)]++;
                say("\nCOMPOSIÇÃO HISTÓRICA DOS 20:");
                for(int g=0;g<4;g++)
                    say(grpName(g)+" = média "+String.format(Locale.US,"%.2f",(double)gc[g]/draws.size()));

                say("\nINICIANDO BACKTEST WALK-FORWARD...");
                say("Agora o aplicativo vai testar TODOS OS 8 PERFIS e depois montar o jogo.");
                say("NÃO FECHE A TELA. O jogo aparece somente depois de 100%.");

                // Mesmo limite móvel anterior: até 80 passos, mas agora realmente usa
                // todas as variáveis do motor Pydroid V3.
                int steps=Math.min(80,draws.size()-151);
                if(steps<1) throw new Exception("Histórico insuficiente para backtest.");

                double[] sum=new double[NAMES.length];
                int[] mx=new int[NAMES.length];

                // Otimização principal: em cada corte histórico, calculamos as features UMA VEZ,
                // e aplicamos os 8 perfis sobre a mesma estrutura.
                List<Map.Entry<Integer,Set<Integer>>> entries=new ArrayList<>(h.entrySet());

                for(int j=0;j<steps;j++){
                    int cut=draws.size()-steps+j-1;
                    TreeMap<Integer,Set<Integer>> sub=new TreeMap<>();
                    for(int k=0;k<=cut;k++){
                        Map.Entry<Integer,Set<Integer>>e=entries.get(k);
                        sub.put(e.getKey(),e.getValue());
                    }

                    Prepared prep=prepare(sub);
                    Set<Integer> target=draws.get(cut+1);

                    for(int p=0;p<NAMES.length;p++){
                        Result r=mountPrepared(prep,p);
                        Set<Integer> q=new HashSet<>(r.game);
                        q.retainAll(target);
                        int ac=q.size();
                        sum[p]+=ac;
                        if(ac>mx[p])mx[p]=ac;
                    }

                    int pct=5+(int)((j+1)*80.0/steps);
                    prog(pct);
                    if((j+1)%5==0 || j==steps-1){
                        say("Backtest: "+(j+1)+"/"+steps+" passos | "+pct+"%");
                    }
                }

                double bestScore=-1;
                int bestP=0;

                say("\nRANKING FINAL DO BACKTEST");
                for(int p=0;p<NAMES.length;p++){
                    double mean=sum[p]/steps;
                    double sc=mean*12+mx[p]*10;
                    say(NAMES[p]+" | média="+String.format(Locale.US,"%.3f",mean)+
                            " | máximo="+mx[p]+
                            " | score="+String.format(Locale.US,"%.2f",sc));
                    if(sc>bestScore){bestScore=sc;bestP=p;}
                }

                say("\nPERFIL ESCOLHIDO: "+NAMES[bestP]);
                say("MONTANDO JOGO FINAL...");

                Prepared finalPrep=prepare(h);
                Result z=mountPrepared(finalPrep,bestP);

                prog(94);

                String report=
                        "PALPITE FINAL - LOTOMANIA V5\n"+
                        "PERFIL: "+NAMES[bestP]+"\n"+
                        "32 DAS 80: "+fmt(z.b32)+"\n"+
                        "GRUPOS NAS 32: "+groupComp(z.b32)+"\n"+
                        "BLOCO 1 - 16: "+fmt(z.b1)+"\n"+
                        "BLOCO 2 - 16: "+fmt(z.b2)+"\n"+
                        "18 DO ÚLTIMO: "+fmt(z.b18)+"\n"+
                        "GRUPOS NAS 18: "+groupComp(z.b18)+"\n"+
                        "2 FORA: "+fmt(z.fora)+"\n"+
                        "JOGO DE 50: "+fmt(z.game)+"\n"+
                        "GRUPOS NO JOGO: "+groupComp(z.game);

                say("\n"+report);
                lastResult=z;
                lastReport=report;

                String pdf=savePdf(report,z);
                prog(100);
                say("\nCONCLUÍDO - JOGO GERADO.");
                say("PDF do volante salvo: "+pdf);
                runOnUiThread(()->pdfButton.setEnabled(true));

            }catch(Exception e){
                say("ERRO: "+e.getClass().getSimpleName()+" - "+e.getMessage());
            }

            runOnUiThread(()->{ open.setEnabled(true); generate.setEnabled(loadedHistory!=null); });
        }).start();
    }

    TreeMap<Integer,Set<Integer>> parse()throws Exception{
        TreeMap<Integer,Set<Integer>>r=new TreeMap<>();
        BufferedReader br=new BufferedReader(new InputStreamReader(
                getContentResolver().openInputStream(chosen), StandardCharsets.UTF_8));
        String l;
        Pattern p=Pattern.compile("\\d+");

        while((l=br.readLine())!=null){
            Matcher m=p.matcher(l);
            ArrayList<Integer>v=new ArrayList<>();
            while(m.find())v.add(Integer.parseInt(m.group()));
            if(v.size()<21)continue;

            int c=v.get(0);
            LinkedHashSet<Integer>d=new LinkedHashSet<>();
            for(int i=1;i<v.size()&&d.size()<20;i++)
                if(v.get(i)>=0&&v.get(i)<=99)d.add(v.get(i));

            if(c>0&&d.size()==20)r.put(c,d);
        }

        br.close();
        if(r.isEmpty())throw new Exception("Não identifiquei concurso + 20 dezenas.");
        return r;
    }

    static class Prepared{
        TreeMap<Integer,Set<Integer>> h;
        List<Set<Integer>> d;
        Set<Integer> last,fail;
        double[][] feat=new double[100][6]; // freq, atraso, subida, persist, stay, enter
        double[] pairFail=new double[100];
        double[] pairLastToFail=new double[100];
        double[][] targets=new double[2][4]; // 0 novas, 1 repetidas
    }

    Prepared prepare(TreeMap<Integer,Set<Integer>>h){
        Prepared pr=new Prepared();
        pr.h=h;
        pr.d=new ArrayList<>(h.values());
        int N=pr.d.size();
        pr.last=new HashSet<>(pr.d.get(N-1));
        pr.fail=new HashSet<>();
        for(int n=0;n<100;n++)if(!pr.last.contains(n))pr.fail.add(n);

        // Frequência / atraso / subida / persistência / transição
        for(int n=0;n<100;n++){
            int f10=0,f20=0,f50=0,f80=0,f120=0,lastAt=-1;
            int stayN=0,stayD=0,enN=0,enD=0;
            ArrayList<Integer> serie=new ArrayList<>();

            for(int i=0;i<N;i++){
                boolean has=pr.d.get(i).contains(n);

                if(has){
                    lastAt=i;
                    if(i>=N-10)f10++;
                    if(i>=N-20)f20++;
                    if(i>=N-50)f50++;
                    if(i>=N-80)f80++;
                    if(i>=N-120)f120++;
                }

                if(i>=Math.max(0,N-40)) serie.add(has?1:0);

                if(i<N-1){
                    if(has){
                        stayD++;
                        if(pr.d.get(i+1).contains(n))stayN++;
                    }else{
                        enD++;
                        if(pr.d.get(i+1).contains(n))enN++;
                    }
                }
            }

            int delay=lastAt<0?N:N-1-lastAt;
            double freq=f10*5+f20*2.7+f50*.9+f80*.45+f120*.2;
            double atr=Math.max(0,12-Math.abs(delay-4)*1.4);
            double sl=Math.max(0,slope(serie))*240;
            double persist=0;
            int st=Math.max(0,serie.size()-20);
            for(int i=st;i<serie.size();i++)persist+=serie.get(i);
            persist=(persist/Math.max(1,serie.size()-st))*38;
            double stay=100.0*(stayN+1)/(stayD+5);
            double enter=100.0*(enN+1)/(enD+5);

            pr.feat[n][0]=freq;
            pr.feat[n][1]=atr;
            pr.feat[n][2]=sl;
            pr.feat[n][3]=persist;
            pr.feat[n][4]=stay;
            pr.feat[n][5]=enter;
        }

        // Pares nas últimas 80 extrações.
        int[][] pc=new int[100][100];
        int begin=Math.max(0,N-80);
        for(int i=begin;i<N;i++){
            ArrayList<Integer>a=new ArrayList<>(pr.d.get(i));
            for(int x=0;x<a.size();x++)
                for(int y=x+1;y<a.size();y++){
                    int u=a.get(x),v=a.get(y);
                    pc[u][v]++;
                    pc[v][u]++;
                }
        }

        for(int n:pr.fail){
            double s=0;
            for(int m:pr.fail)if(m!=n)s+=pc[n][m];
            pr.pairFail[n]=s/79.0;
        }

        for(int n:pr.last){
            double s=0;
            for(int m:pr.fail)s+=pc[n][m];
            pr.pairLastToFail[n]=s/80.0;
        }

        // Alvos de grupos aprendidos para NOVAS e REPETIDAS
        ArrayList<int[]> novaHist=new ArrayList<>();
        ArrayList<int[]> repHist=new ArrayList<>();

        for(int i=1;i<N;i++){
            Set<Integer>prev=pr.d.get(i-1),cur=pr.d.get(i);
            int[] nr=new int[4], rr=new int[4];
            for(int n:cur){
                if(prev.contains(n))rr[grp(n)]++;
                else nr[grp(n)]++;
            }
            novaHist.add(nr);
            repHist.add(rr);
        }

        for(int g=0;g<4;g++){
            pr.targets[0][g]=target(novaHist,g);
            pr.targets[1][g]=target(repHist,g);
        }

        return pr;
    }

    double target(ArrayList<int[]> hist,int g){
        if(hist.isEmpty())return 0;
        int size=hist.size();
        int start=Math.max(0,size-80);
        ArrayList<Integer> recent=new ArrayList<>();
        double all=0;
        for(int[]x:hist)all+=x[g];
        for(int i=start;i<size;i++)recent.add(hist.get(i)[g]);
        Collections.sort(recent);
        double med;
        if(recent.size()%2==1)med=recent.get(recent.size()/2);
        else med=(recent.get(recent.size()/2-1)+recent.get(recent.size()/2))/2.0;
        return .70*med+.30*(all/hist.size());
    }

    static class Result{
        Set<Integer>game,b32,b18,fora,b1,b2;
    }

    Result mountPrepared(Prepared pr,int p){
        double[] w=W[p];
        double[] scoreFail=new double[100];
        double[] scoreLast=new double[100];

        for(int n:pr.fail){
            scoreFail[n]=
                    w[0]*pr.feat[n][0]+
                    w[1]*pr.feat[n][1]+
                    w[2]*pr.feat[n][2]+
                    w[3]*pr.feat[n][3]+
                    w[4]*pr.pairFail[n]+
                    w[5]*pr.feat[n][5];
        }

        Set<Integer>b32=selectWithGroupTarget(
                pr.fail,32,scoreFail,pr.targets[0],w[6]
        );

        // Recalcula pair médio dos 20 do último contra as 32 escolhidas
        // com a matriz implícita da última janela através de score proporcional.
        for(int n:pr.last){
            scoreLast[n]=
                    w[0]*pr.feat[n][0]+
                    w[1]*pr.feat[n][1]+
                    w[2]*pr.feat[n][2]+
                    w[3]*pr.feat[n][3]+
                    w[4]*pr.pairLastToFail[n]+
                    w[5]*pr.feat[n][4];
        }

        Set<Integer>b18=selectWithGroupTarget(
                pr.last,18,scoreLast,pr.targets[1],w[6]
        );

        Set<Integer>fora=new HashSet<>(pr.last);
        fora.removeAll(b18);

        Set<Integer>game=new HashSet<>(b32);
        game.addAll(b18);

        // Divide as 32 em 2 grupos equilibrados de 16 por score.
        ArrayList<Integer>x=new ArrayList<>(b32);
        x.sort((a,b)->Double.compare(scoreFail[b],scoreFail[a]));
        Set<Integer>b1=new LinkedHashSet<>(),b2=new LinkedHashSet<>();
        double s1=0,s2=0;
        for(int n:x){
            if(b1.size()>=16){b2.add(n);s2+=scoreFail[n];}
            else if(b2.size()>=16){b1.add(n);s1+=scoreFail[n];}
            else if(s1<=s2){b1.add(n);s1+=scoreFail[n];}
            else {b2.add(n);s2+=scoreFail[n];}
        }

        Result z=new Result();
        z.game=game;
        z.b32=b32;
        z.b18=b18;
        z.fora=fora;
        z.b1=b1;
        z.b2=b2;
        return z;
    }

    Set<Integer> selectWithGroupTarget(Set<Integer> cands,int qtd,double[] score,double[] targets,double wg){
        LinkedHashSet<Integer> chosen=new LinkedHashSet<>();
        int[] counts=new int[4];

        while(chosen.size()<qtd){
            double best=-Double.MAX_VALUE;
            int bestN=-1;

            for(int n:cands){
                if(chosen.contains(n))continue;

                int g=grp(n);
                double need=targets[g]-counts[g];
                double gb=(need>0?need*12.0:need*18.0)*wg;
                double sc=score[n]+gb;

                if(sc>best){
                    best=sc;
                    bestN=n;
                }
            }

            if(bestN<0)break;
            chosen.add(bestN);
            counts[grp(bestN)]++;
        }

        return chosen;
    }

    double slope(List<Integer>v){
        int n=v.size();
        if(n<2)return 0;
        double xm=(n-1)/2.0,ym=0;
        for(int x:v)ym+=x;
        ym/=n;
        double den=0,num=0;
        for(int i=0;i<n;i++){
            double dx=i-xm;
            den+=dx*dx;
            num+=dx*(v.get(i)-ym);
        }
        return den==0?0:num/den;
    }

    String groupComp(Collection<Integer> nums){
        int[]c=new int[4];
        for(int n:nums)c[grp(n)]++;
        return "PF="+c[0]+" | PAR="+c[1]+" | ÍMPAR="+c[2]+" | OUTROS="+c[3];
    }

    String savePdf(String report,Result z)throws Exception{
        PdfDocument doc=new PdfDocument();
        PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
        Canvas c=page.getCanvas();
        Paint p=new Paint(1);

        p.setColor(Color.rgb(20,130,55));
        p.setTextSize(19); p.setFakeBoldText(true);
        c.drawText("LOTOMANIA V5 - VOLANTE DO JOGO",32,42,p);
        p.setColor(Color.BLACK); p.setTextSize(10); p.setFakeBoldText(false);
        c.drawText("50 dezenas do motor em VERDE • 50 falhas em BRANCO",32,62,p);

        Set<Integer> jogo=new HashSet<>(z.game);
        float left=32, top=88, cellW=52, cellH=48;
        Paint fill=new Paint(1), border=new Paint(1), text=new Paint(1);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(1.2f); border.setColor(Color.rgb(110,110,110));
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(15); text.setFakeBoldText(true);

        for(int row=0;row<10;row++){
            for(int col=0;col<10;col++){
                int n=row*10+col;
                float x=left+col*cellW, y=top+row*cellH;
                if(jogo.contains(n)){
                    fill.setColor(Color.rgb(35,160,70));
                    text.setColor(Color.WHITE);
                }else{
                    fill.setColor(Color.WHITE);
                    text.setColor(Color.BLACK);
                }
                c.drawRoundRect(x,y,x+46,y+40,6,6,fill);
                c.drawRoundRect(x,y,x+46,y+40,6,6,border);
                c.drawText(String.format(Locale.US,"%02d",n),x+23,y+26,text);
            }
        }

        float infoY=590;
        p.setTextSize(10); p.setFakeBoldText(true); p.setColor(Color.rgb(20,130,55));
        c.drawText("JOGO DE 50",32,infoY,p);
        p.setColor(Color.BLACK); p.setFakeBoldText(false); p.setTextSize(8.5f);
        int yy=(int)infoY+16;
        for(String part:wrap(fmt(z.game),105)){ c.drawText(part,32,yy,p); yy+=13; }
        c.drawText("32 das 80: "+fmt(z.b32),32,yy+5,p); yy+=18;
        c.drawText("18 do último: "+fmt(z.b18),32,yy+5,p); yy+=18;
        c.drawText("2 do último fora: "+fmt(z.fora),32,yy+5,p);

        doc.finishPage(page);

        ContentValues cv=new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME,"LOTOMANIA_V5_VOLANTE_VERDE.pdf");
        cv.put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");
        if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download");
        Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
        if(u==null)throw new Exception("Não consegui criar o PDF na pasta Download.");
        OutputStream os=getContentResolver().openOutputStream(u);
        if(os==null)throw new Exception("Não consegui abrir o PDF para gravação.");
        doc.writeTo(os); os.close(); doc.close();
        return "Download/LOTOMANIA_V5_VOLANTE_VERDE.pdf";
    }

    List<String>wrap(String s,int n){
        ArrayList<String>r=new ArrayList<>();
        while(s.length()>n){
            r.add(s.substring(0,n));
            s=s.substring(n);
        }
        r.add(s);
        return r;
    }
}
