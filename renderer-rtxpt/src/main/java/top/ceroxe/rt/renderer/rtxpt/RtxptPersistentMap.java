package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import java.util.function.BiConsumer;

/** Persistent AVL index. Transaction snapshots share untouched paths, never mutable nodes. */
class RtxptPersistentMap<V> extends AbstractMap<Long,V> {
    private record Node<V>(long key,V value,Node<V> left,Node<V> right,int height,int count) {
        Node(long key,V value,Node<V> left,Node<V> right) {
            this(key,value,left,right,1+Math.max(height(left),height(right)),1+count(left)+count(right));
        }
        private static int height(Node<?> n){return n==null?0:n.height;}
        private static int count(Node<?> n){return n==null?0:n.count;}
    }
    private Node<V> root;
    RtxptPersistentMap() { }
    RtxptPersistentMap(RtxptPersistentMap<V> source){root=source.root;}
    boolean sameSnapshot(RtxptPersistentMap<?> other){return root==other.root;}
    V valueAt(int index){
        Objects.checkIndex(index,size());
        for(var node=root;;){int left=Node.count(node.left);if(index==left)return node.value;
            if(index<left)node=node.left;else{index-=left+1;node=node.right;}}
    }
    /** Visits changed values and removals, skipping shared subtrees even after AVL rotations.
     * The return value counts nodes actually inspected, not the size of either snapshot. */
    long forEachChanged(RtxptPersistentMap<V> before,BiConsumer<Long,V> action){
        if(root==before.root)return 0;
        return changed(root,before,action,false)+changed(before.root,this,action,true);
    }
    private static <V> long changed(Node<V> current,RtxptPersistentMap<V> other,BiConsumer<Long,V> action,boolean removals){
        if(current==null)return 0;
        long inspected=1;var previous=other.root;
        while(previous!=null){inspected++;if(previous.key==current.key)break;
            previous=current.key<previous.key?previous.left:previous.right;}
        if(current==previous)return inspected;
        if(removals){if(previous==null)action.accept(current.key,null);}
        else if(previous==null||current.value!=previous.value)action.accept(current.key,current.value);
        return inspected+changed(current.left,other,action,removals)+changed(current.right,other,action,removals);
    }
    private static <V> Node<V> rotateLeft(Node<V> n){var r=n.right;return new Node<>(r.key,r.value,new Node<>(n.key,n.value,n.left,r.left),r.right);}
    private static <V> Node<V> rotateRight(Node<V> n){var l=n.left;return new Node<>(l.key,l.value,l.left,new Node<>(n.key,n.value,l.right,n.right));}
    private static <V> Node<V> balance(Node<V> n){
        int b=Node.height(n.left)-Node.height(n.right);
        if(b>1){if(Node.height(n.left.left)<Node.height(n.left.right))n=new Node<>(n.key,n.value,rotateLeft(n.left),n.right);return rotateRight(n);}
        if(b< -1){if(Node.height(n.right.right)<Node.height(n.right.left))n=new Node<>(n.key,n.value,n.left,rotateRight(n.right));return rotateLeft(n);}
        return n;
    }
    private static <V> Node<V> put(Node<V> n,long key,V value){
        if(n==null)return new Node<>(key,value,null,null);
        if(key==n.key)return value==n.value?n:new Node<>(key,value,n.left,n.right);
        var l=key<n.key?put(n.left,key,value):n.left;var r=key>n.key?put(n.right,key,value):n.right;
        return l==n.left&&r==n.right?n:balance(new Node<>(n.key,n.value,l,r));
    }
    private static <V> Node<V> remove(Node<V> n,long key){
        if(n==null)return null;
        if(key==n.key){
            if(n.left==null)return n.right;if(n.right==null)return n.left;
            var next=n.right;while(next.left!=null)next=next.left;
            return balance(new Node<>(next.key,next.value,n.left,remove(n.right,next.key)));
        }
        var l=key<n.key?remove(n.left,key):n.left;var r=key>n.key?remove(n.right,key):n.right;
        return l==n.left&&r==n.right?n:balance(new Node<>(n.key,n.value,l,r));
    }
    @Override public V get(Object key){
        if(!(key instanceof Long k))return null;
        for(var n=root;n!=null;n=k<n.key?n.left:n.right)if(k==n.key)return n.value;
        return null;
    }
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public V put(Long key,V value){Objects.requireNonNull(key);Objects.requireNonNull(value);var old=get(key);root=put(root,key,value);return old;}
    @Override public V remove(Object key){var old=get(key);if(old!=null)root=remove(root,((Long)key).longValue());return old;}
    @Override public void clear(){root=null;}
    @Override public int size(){return Node.count(root);}
    @Override public Set<Entry<Long,V>> entrySet(){
        return new AbstractSet<>() {
            @Override public int size(){return RtxptPersistentMap.this.size();}
            @Override public Iterator<Entry<Long,V>> iterator(){
                return new Iterator<>() {
                    final ArrayDeque<Node<V>> stack=new ArrayDeque<>();
                    {push(root);}
                    private void push(Node<V> n){while(n!=null){stack.push(n);n=n.left;}}
                    public boolean hasNext(){return !stack.isEmpty();}
                    public Entry<Long,V> next(){if(stack.isEmpty())throw new NoSuchElementException();var n=stack.pop();push(n.right);return Map.entry(n.key,n.value);}
                };
            }
        };
    }
}
