package com.hsp1020.elevate

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** HTTP Range MKV text-subtitle extractor. No fallback to whole-video downloads.
 * The EBML/Cues traversal follows cryguy/mkv-subtitle-extractor (MIT).
 */
internal class MkvSubtitleRangeExtractor(private val link: ExtractorLink) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).build()
    private val headers = link.headers.filterKeys { !it.equals("Range", true) }.toMutableMap().apply {
        if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) })
            put("Referer", link.referer)
    }
    private var size = 0L
    private var totalRead = 0L
    private var requests = 0
    private var lastOffset = -1L
    private var lastData = byteArrayOf()
    private data class Element(val id: Long, val data: Int, val length: Long) {
        val end: Long get() = data.toLong() + length
    }
    private data class Track(val id: Int, val codec: String, val language: String)
    private data class Cue(val time: Long, val cluster: Long, val relative: Long?)
    private data class Line(val time: Long, val duration: Long?, val text: String)

    private fun width(v: Int): Int {
        for (w in 1..8) if (v and (0x80 ushr (w - 1)) != 0) return w
        throw IOException("Invalid EBML VINT")
    }
    private fun number(b: ByteArray, at: Int, length: Int): Long {
        if (at < 0 || length !in 1..8 || at + length > b.size)
            throw IOException("Truncated EBML number")
        var n = 0L
        for (i in 0 until length) n = (n shl 8) or (b[at+i].toLong() and 255)
        return n
    }
    private fun element(b: ByteArray, at: Int): Element {
        if (at !in b.indices) throw IOException("Missing EBML element")
        val iw = width(b[at].toInt() and 255)
        if (iw > 4 || at + iw >= b.size) throw IOException("Invalid EBML id")
        val id = number(b, at, iw)
        val p = at + iw
        val sw = width(b[p].toInt() and 255)
        if (p + sw > b.size) throw IOException("Invalid EBML length")
        val mask = (1 shl (8-sw))-1
        var length = (b[p].toInt() and mask).toLong()
        var unknown = length == mask.toLong()
        for (i in 1 until sw) {
            val byte = b[p+i].toInt() and 255
            length = (length shl 8) or byte.toLong()
            unknown = unknown && byte == 255
        }
        return Element(id, p+sw, if (unknown) -1 else length)
    }
    private fun children(b: ByteArray, start: Int, end: Int): List<Element> {
        val out = ArrayList<Element>()
        var offset = start
        while (offset + 2 <= min(end, b.size)) {
            val e = runCatching { element(b, offset) }.getOrNull() ?: break
            if (e.length < 0 || e.end > end || e.end > b.size || e.end <= offset) break
            out.add(e)
            offset = e.end.toInt()
        }
        return out
    }
    private fun inside(b: ByteArray, e: Element) = children(b,e.data,e.end.toInt())
    private fun child(b: ByteArray, e: Element, id: Long) = inside(b,e).firstOrNull { it.id==id }
    private fun num(b: ByteArray,e: Element) = if (e.length in 1L..8L)
        number(b,e.data,e.length.toInt()) else 0L
    private fun txt(b: ByteArray,e: Element) =
        String(b,e.data,e.length.toInt(),Charsets.UTF_8).trimEnd('\u0000')

    private fun read(at: Long, count: Int): ByteArray {
        if (at < 0 || count <= 0 || size > 0 && at >= size) throw IOException("Bad range")
        val n = if (size > 0) min(count.toLong(),size-at).toInt() else count
        if (at >= lastOffset && lastOffset >= 0 && at+n <= lastOffset+lastData.size) {
            val from=(at-lastOffset).toInt()
            return lastData.copyOfRange(from,from+n)
        }
        val bytes = if (size>0) min(maxOf(n,16*1024).toLong(),size-at).toInt() else n
        if (bytes > 4*1024*1024 || requests >= 1800 ||
            totalRead + bytes > 32L*1024*1024) throw IOException("MKV Range limit reached")
        val request = Request.Builder().url(link.url).apply {
            headers.forEach { (k,v) -> header(k,v) }
            header("Range","bytes="+at+"-"+(at+bytes-1))
        }.get().build()
        requests++
        client.newCall(request).execute().use { res ->
            if (res.code != 206) throw IOException("Server ignored HTTP Range")
            val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
                .matchEntire(res.header("Content-Range").orEmpty().trim())
                ?: throw IOException("Invalid Content-Range")
            val from=m.groupValues[1].toLong()
            val to=m.groupValues[2].toLong()
            val length=m.groupValues[3].toLong()
            if (from != at || to != at+bytes-1 || length <= to ||
                size > 0 && size != length) throw IOException("Wrong Range response")
            size=length
            val data=ByteArray(bytes)
            val stream=res.body?.byteStream() ?: throw IOException("Empty Range")
            var pos=0
            while(pos<bytes) {
                val got=stream.read(data,pos,bytes-pos)
                if(got<=0) throw IOException("Short Range body")
                pos+=got
            }
            if (stream.read()!=-1) throw IOException("Oversized Range body")
            totalRead+=bytes
            lastOffset=at
            lastData=data
            return data.copyOfRange(0,n)
        }
    }
    private fun readElement(at: Long): ByteArray {
        val head=element(read(at,16),0)
        if(head.length<0 || head.length+head.data>4*1024*1024)
            throw IOException("MKV metadata element too large")
        return read(at,(head.length+head.data).toInt())
    }
    private fun seek(b: ByteArray,e: Element): Map<Long,Long> {
        val map=LinkedHashMap<Long,Long>()
        for(item in inside(b,e).filter { it.id==0x4DBBL }) {
            val id=child(b,item,0x53ABL)?.let { num(b,it) } ?: continue
            val pos=child(b,item,0x53ACL)?.let { num(b,it) } ?: continue
            map[id]=pos
        }
        return map
    }
    private fun readTracks(data: ByteArray): List<Track> {
        val root=element(data,0)
        if(root.id!=0x1654AE6BL) throw IOException("MKV tracks missing")
        return inside(data,root).filter { it.id==0xAEL }.mapNotNull { entry ->
            if(child(data,entry,0x83L)?.let { num(data,it) }!=17L) return@mapNotNull null
            val id=child(data,entry,0xD7L)?.let { num(data,it).toInt() } ?: return@mapNotNull null
            val codec=child(data,entry,0x86L)?.let { txt(data,it) }.orEmpty()
            val lang=child(data,entry,0x22B59DL)?.let { txt(data,it) }
                ?: child(data,entry,0x22B59CL)?.let { txt(data,it) } ?: "und"
            Track(id,codec,lang)
        }
    }
    private fun readCues(data: ByteArray,id: Int): List<Cue> {
        val root=element(data,0)
        if(root.id!=0x1C53BB6BL) return emptyList()
        val out=ArrayList<Cue>()
        for(point in inside(data,root).filter { it.id==0xBBL }) {
            val time=child(data,point,0xB3L)?.let { num(data,it) } ?: continue
            for(p in inside(data,point).filter { it.id==0xB7L }) {
                if(child(data,p,0xF7L)?.let { num(data,it).toInt() }!=id) continue
                val cluster=child(data,p,0xF1L)?.let { num(data,it) } ?: continue
                val relative=child(data,p,0xF0L)?.let { num(data,it) }
                out.add(Cue(time,cluster,relative))
            }
        }
        return out
    }
    private fun block(b:ByteArray,at:Int,size:Int,id:Int,whenMs:Long,duration:Long?,codec:String): Line? {
        if(size<4 || at<0 || at+size>b.size) return null
        val tw=width(b[at].toInt() and 255)
        if(size<tw+3) return null
        val track=(number(b,at,tw) and ((1L shl (7*tw))-1)).toInt()
        if(track!=id) return null
        val raw=String(b,at+tw+3,size-tw-3,Charsets.UTF_8).trimEnd('\u0000')
        val value=if(codec=="S_TEXT/ASS" || codec=="S_TEXT/SSA") {
            var p=0
            repeat(8) {
                p=raw.indexOf(',',p)
                if(p<0) return null
                p++
            }
            raw.substring(p).replace(Regex("""\{[^}]*\}"""),"")
                .replace("\\N","\n").replace("\\n","\n").replace("\\h"," ")
        } else raw
        val valueTrimmed=value.trim()
        return if(valueTrimmed.isEmpty()) null else Line(whenMs,duration,valueTrimmed)
    }
    private fun group(b:ByteArray,e:Element,id:Int,whenMs:Long,scale:Double,codec:String):Line? {
        val payload=child(b,e,0xA1L) ?: return null
        val dur=child(b,e,0x9BL)?.let { (num(b,it)*scale).toLong() }
        return block(b,payload.data,payload.length.toInt(),id,whenMs,dur,codec)
    }
    private fun indexed(at:Long,id:Int,whenMs:Long,scale:Double,codec:String):Line? {
        val b=readElement(at)
        val e=element(b,0)
        return when(e.id) {
            0xA0L -> group(b,e,id,whenMs,scale,codec)
            0xA1L,0xA3L -> block(b,e.data,e.length.toInt(),id,whenMs,null,codec)
            else -> null
        }
    }
    private fun scanCluster(at:Long,id:Int,scale:Double,codec:String):List<Line> {
        val cluster=element(read(at,16),0)
        if(cluster.id!=0x1F43B675L) return emptyList()
        val end=if(cluster.length<0) size else at+cluster.data+cluster.length
        var offset=at+cluster.data
        var timestamp=0L
        val result=ArrayList<Line>()
        while(offset+16<end) {
            val e=element(read(offset,16),0)
            if(e.length<0 || offset+e.end>end) break
            val dataOffset=offset+e.data
            when(e.id) {
                0xE7L -> timestamp=num(read(dataOffset,e.length.toInt()),
                    Element(0,0,e.length))
                0xA3L -> if(e.length in 4L..131072L) {
                    val peek=read(dataOffset,8)
                    val w=width(peek[0].toInt() and 255)
                    if((number(peek,0,w) and ((1L shl (7*w))-1)).toInt()==id) {
                        val bytes=read(dataOffset,e.length.toInt())
                        val rel=((bytes[w].toInt() shl 8) or (bytes[w+1].toInt() and 255)).toShort().toLong()
                        block(bytes,0,bytes.size,id,((timestamp+rel)*scale).toLong(),null,codec)?.let(result::add)
                    }
                }
                0xA0L -> if(e.length in 5L..131072L) {
                    val peek=read(dataOffset,min(64L,e.length).toInt())
                    val first=runCatching { element(peek,0) }.getOrNull()
                    if(first?.id==0xA1L && first.data<peek.size) {
                        val w=width(peek[first.data].toInt() and 255)
                        if((number(peek,first.data,w) and ((1L shl (7*w))-1)).toInt()==id) {
                            val bytes=read(dataOffset,e.length.toInt())
                            val header=element(bytes,0)
                            val rel=((bytes[header.data+w].toInt() shl 8) or
                                (bytes[header.data+w+1].toInt() and 255)).toShort().toLong()
                            group(bytes,Element(0,0,bytes.size.toLong()),id,
                                ((timestamp+rel)*scale).toLong(),scale,codec)?.let(result::add)
                        }
                    }
                }
            }
            offset=dataOffset+e.length
        }
        return result
    }

    suspend fun extract(trackId:String,language:String?):String = withContext(Dispatchers.IO) {
        if(!link.url.startsWith("https://",true) && !link.url.startsWith("http://",true))
            throw IOException("Only HTTP MKV streams supported")
        read(0,256*1024)
        val init=read(0,min(size,256*1024).toInt())
        val ebml=element(init,0)
        if(ebml.id!=0x1A45DFA3L || ebml.length<0) throw IOException("Not an EBML/MKV stream")
        val seg=element(init,ebml.end.toInt())
        if(seg.id!=0x18538067L) throw IOException("MKV Segment missing")
        val base=seg.data.toLong()
        val positions=LinkedHashMap<Long,Long>()
        for(e in children(init,seg.data,init.size)) {
            if(e.id==0x114D9B74L) positions.putAll(seek(init,e))
            if(e.id==0x1F43B675L) break
        }
        val tracksAt=positions[0x1654AE6BL] ?: throw IOException("MKV SeekHead lacks tracks")
        val codecs=setOf("S_TEXT/UTF8","S_TEXT/ASS","S_TEXT/SSA","S_TEXT/WEBVTT")
        val supported=readTracks(readElement(base+tracksAt)).filter { it.codec in codecs }
        val track=supported.firstOrNull { it.id.toString()==trackId }
            ?: supported.singleOrNull { it.language.equals(language,true) }
            ?: throw IOException("Selected MKV text track not found")
        var scale=1.0
        positions[0x1549A966L]?.let { loc ->
            val bytes=readElement(base+loc);val root=element(bytes,0)
            child(bytes,root,0x2AD7B1L)?.let { scale=num(bytes,it)/1_000_000.0 }
        }
        val cuesAt=positions[0x1C53BB6BL]
        val cues=if(cuesAt!=null) readCues(readElement(base+cuesAt),track.id)
            else emptyList()
        val found=ArrayList<Line>()
        val seenClusters=HashSet<Long>()
        if(cues.isEmpty()) {
            // Without subtitle Cues, read only segment/cluster headers and selected blocks.
            var offset=base
            val end=if(seg.length<0) size else min(size,base+seg.length)
            while(offset+16<end) {
                currentCoroutineContext().ensureActive()
                val head=element(read(offset,16),0)
                if(head.length<0 || offset+head.end>end) break
                if(head.id==0x1F43B675L)
                    found.addAll(scanCluster(offset,track.id,scale,track.codec))
                offset+=head.end
            }
        } else for(cue in cues) {
            currentCoroutineContext().ensureActive()
            val cluster=base+cue.cluster
            if(cue.relative!=null) {
                val head=element(read(cluster,16),0)
                indexed(cluster+head.data+cue.relative,track.id,
                    (cue.time*scale).toLong(),scale,track.codec)?.let(found::add)
            } else if(seenClusters.add(cue.cluster)) {
                found.addAll(scanCluster(cluster,track.id,scale,track.codec))
            }
        }
        val clean=found.filter { it.time>=0 }.distinctBy { it.time to it.text }.sortedBy { it.time }
        if(clean.isEmpty()) throw IOException("No MKV text cues extracted")
        val srt=buildString {
            for((index,line) in clean.withIndex()) {
                val next=clean.getOrNull(index+1)?.time
                val length=line.duration?.takeIf { it>0 }
                    ?: next?.let { (it-line.time).coerceIn(500L,7000L) } ?: 4000L
                append(index+1).append('\n')
                append(stamp(line.time)).append(" --> ")
                    .append(stamp(line.time+length)).append('\n')
                append(line.text).append("\n\n")
            }
        }
        if(srt.toByteArray().size>5*1024*1024)
            throw IOException("SubMaker accepts at most 5 MB")
        srt
    }
    private fun stamp(ms:Long):String {
        val t=ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,
            t/3600000,(t/60000)%60,(t/1000)%60,t%1000)
    }
}
