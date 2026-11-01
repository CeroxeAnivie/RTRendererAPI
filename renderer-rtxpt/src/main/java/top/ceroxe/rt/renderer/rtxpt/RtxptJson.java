package top.ceroxe.rt.renderer.rtxpt;
import java.util.*;

/** Small asset serialization helper; never used in the steady-state frame path. */
final class RtxptJson {
    static Map<String,Object> object(Object... pairs){
        var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;
    }
    static String encode(Object v){
        if(v==null)return "null";
        if(v instanceof String s){var o=new StringBuilder("\"");for(int i=0;i<s.length();i++){
            char c=s.charAt(i);switch(c){case '"'->o.append("\\\"");case '\\'->o.append("\\\\");case '\n'->o.append("\\n");case '\r'->o.append("\\r");case '\t'->o.append("\\t");default->{if(c<32)o.append(String.format("\\u%04x",(int)c));else o.append(c);}}
        }return o.append('"').toString();}
        if(v instanceof Number || v instanceof Boolean)return v.toString();
        if(v instanceof Map<?,?> m){var a=new StringJoiner(",","{","}");m.forEach((k,x)->a.add(encode(k.toString())+":"+encode(x)));return a.toString();}
        if(v instanceof Iterable<?> l){var a=new StringJoiner(",","[","]");l.forEach(x->a.add(encode(x)));return a.toString();}
        if(v.getClass().isArray()){var a=new StringJoiner(",","[","]");for(int i=0;i<java.lang.reflect.Array.getLength(v);i++)a.add(encode(java.lang.reflect.Array.get(v,i)));return a.toString();}
        throw new IllegalArgumentException("Unsupported JSON value "+v.getClass());
    }
}
