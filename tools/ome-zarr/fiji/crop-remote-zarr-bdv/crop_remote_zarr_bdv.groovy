#@ String  (label="OME-Zarr URL (multiscale group)", description="https://... or s3://...") url
#@ File    (label="Output TIFF", style="save") outFile
#@ Integer (label="Download threads", value=8, min=1, max=64) nThreads
#@ Boolean (label="Require physical calibration", value=true, description="Stop if the OME-Zarr has no voxel size and unit, rather than saving in pixels") requireCalibration

/*
 * Crop a region from a remote OME-Zarr and save it as a local TIFF.
 *
 * Streams the multi-resolution pyramid into BigDataViewer, lets you choose a
 * 3D box with BDV's interactive box tool (the same one MoBIE's Select Box
 * uses), then downloads only that box at full resolution.
 *
 * Input:  the URL of an OME-Zarr multiscale group, v0.4 (Zarr v2) or
 *         v0.5 (Zarr v3), over https:// or s3://.
 * Output: the crop as a TIFF, with voxel size, unit and origin set, and
 *         <name>.provenance.json beside it, recording the parameters, source,
 *         crop box, ImageJ version and this script's checksum.
 *
 * Only the first channel and timepoint are shown and exported.
 *
 * Run it from Fiji's Script Editor (Language > Groovy). It needs a screen to
 * choose the box, so unlike most Fiji scripts it can't run headless. It
 * needs a current Fiji: N5, n5-zarr and bigdataviewer-vistools ship with it.
 */

import org.janelia.saalfeldlab.n5.universe.N5Factory
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import com.google.gson.JsonElement
import bdv.util.BdvFunctions
import bdv.util.BdvOptions
import bdv.util.RandomAccessibleIntervalMipmapSource
import bdv.util.volatiles.SharedQueue
import bdv.util.volatiles.VolatileViews
import bdv.tools.boundingbox.BoxSelectionOptions
import mpicbg.spim.data.sequence.FinalVoxelDimensions
import net.imglib2.RandomAccessibleInterval
import net.imglib2.FinalInterval
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.view.Views
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.loops.LoopBuilder
import java.util.function.BiConsumer
import net.imglib2.img.planar.PlanarImgFactory
import net.imglib2.img.display.imagej.ImageJFunctions
import ij.IJ
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.time.OffsetDateTime

url = url.replaceAll('/+$', '')
def n5 = new N5Factory().openReader(url)

// ---------- read multiscales metadata -------------------------------------
JsonElement ms = null
for (key in ["multiscales", "ome/multiscales"]) {
    try { ms = n5.getAttribute("", key, JsonElement) } catch (ignored) {}
    if (ms != null) break
}
if (ms == null) { IJ.error("No OME-Zarr 'multiscales' metadata found at\n" + url); return }
def m0 = ms.getAsJsonArray().get(0).getAsJsonObject()
def datasets = m0.getAsJsonArray("datasets")
def paths = (0..<datasets.size()).collect { datasets.get(it).getAsJsonObject().get("path").getAsString() }

def attrs0 = n5.getDatasetAttributes(paths[0])
int nd = attrs0.getNumDimensions()
// axis names in N5 order (= reversed Zarr order)
def names
String unit = null
if (m0.has("axes")) {
    def ax = m0.getAsJsonArray("axes")
    names = (0..<ax.size()).collect {
        def a = ax.get(it); a.isJsonObject() ? a.getAsJsonObject().get("name").getAsString() : a.getAsString()
    }.reverse()
    def xAx = (0..<ax.size()).collect { ax.get(it) }.find { it.isJsonObject() && it.getAsJsonObject().get("name").getAsString() == "x" }
    if (xAx != null && xAx.getAsJsonObject().has("unit")) unit = xAx.getAsJsonObject().get("unit").getAsString()
} else {
    names = ["t", "c", "z", "y", "x"].takeRight(nd).reverse()
}
def spatial = ["x", "y", "z"].collect { names.indexOf(it) }

// per-level physical voxel size (x,y,z)
boolean hasScale = true
def scales = paths.withIndex().collect { p, i ->
    def ds = datasets.get(i).getAsJsonObject()
    def s = null
    if (ds.has("coordinateTransformations")) {
        def cts = ds.getAsJsonArray("coordinateTransformations")
        def sc = (0..<cts.size()).collect { cts.get(it).getAsJsonObject() }.find { it.get("type").getAsString() == "scale" }
        if (sc != null) {
            def arr = sc.getAsJsonArray("scale")
            def rev = (0..<arr.size()).collect { arr.get(it).getAsDouble() }.reverse()
            s = spatial.collect { it >= 0 ? rev[it] : 1.0d }
        }
    }
    if (s == null) {   // no metadata: infer relative scales from array shapes
        if (i == 0) hasScale = false
        def d0 = attrs0.getDimensions(), dk = n5.getDatasetAttributes(p).getDimensions()
        s = spatial.collect { it >= 0 ? (double) d0[it] / dk[it] : 1.0d }
    }
    s as double[]
}

// A crop saved in pixels silently loses its link to physical space, so stop
// unless the user has said that's acceptable.
if (!hasScale || unit == null) {
    def why = !hasScale ? "no scale in its coordinateTransformations" : "no unit on its x axis"
    if (requireCalibration) {
        IJ.error("Crop remote Zarr", "This OME-Zarr has " + why + ", so its voxel size is unknown.\n" +
                "Untick 'Require physical calibration' to save the crop in pixels.")
        return
    }
    IJ.log("Warning: OME-Zarr has " + why + "; the crop is calibrated in pixels.")
    unit = "pixel"
}

// reduce an N-D N5 image to 3D x,y,z (index 0 along c/t; add z if 2D)
def to3D = { RandomAccessibleInterval img ->
    for (int d = nd - 1; d >= 0; d--)
        if (!(names[d] in ["x", "y", "z"])) img = Views.hyperSlice(img, d, 0)
    if (img.numDimensions() == 2) img = Views.addDimension(img, 0, 0)
    img
}

// ---------- show in BigDataViewer (lazy, cached, multi-resolution) --------
def queue = new SharedQueue(nThreads, paths.size())
def volLevels = paths.collect { to3D(VolatileViews.wrapAsVolatile(N5Utils.openVolatile(n5, it), queue)) }
def s0 = scales[0]
def srcT = new AffineTransform3D()
srcT.set(s0[0], 0, 0, 0,  0, s0[1], 0, 0,  0, 0, s0[2], 0)
double[][] mip = scales.collect { s -> [s[0] / s0[0], s[1] / s0[1], s[2] / s0[2]] as double[] } as double[][]
def source = new RandomAccessibleIntervalMipmapSource(
        volLevels as RandomAccessibleInterval[], Util.getTypeFromInterval(volLevels[0]),
        mip, new FinalVoxelDimensions(unit, s0), srcT, url.tokenize('/')[-1])
def bdv = BdvFunctions.show(source, BdvOptions.options().frameTitle("Remote Zarr - choose box"))

// ---------- interactive bounding box (in full-res voxel coordinates) ------
def full = to3D(N5Utils.open(n5, paths[0]))       // full-res, lazy, non-volatile
long[] dims = Intervals.dimensionsAsLongArray(full)
def range = new FinalInterval(dims)
def init = Intervals.createMinMax(
        dims[0].intdiv(4), dims[1].intdiv(4), dims[2].intdiv(4),
        3 * dims[0].intdiv(4), 3 * dims[1].intdiv(4), Math.max(0, 3 * dims[2].intdiv(4)))
IJ.log("Adjust the box in BDV (press S for brightness/contrast), then click OK.")
def res = BdvFunctions.selectBox(bdv, srcT, init, range,
        BoxSelectionOptions.options().title("Select region to export"))
if (!res.isValid()) { IJ.log("Cancelled."); return }
def box = res.getInterval()

// ---------- confirm size --------------------------------------------------
def type = Util.getTypeFromInterval(full)
long nPix = Intervals.numElements(box)
double mb = nPix * type.getBitsPerPixel() / 8.0 / (1024 * 1024)
IJ.log(String.format("Box (level-0 voxels): min=%s max=%s  -> %s  (~%.0f MB)",
        Arrays.toString(Intervals.minAsLongArray(box)), Arrays.toString(Intervals.maxAsLongArray(box)),
        Arrays.toString(Intervals.dimensionsAsLongArray(box)), mb))
if (!IJ.showMessageWithCancel("Export", String.format("Download ~%.0f MB and save to\n%s ?", mb, outFile))) return

// ---------- download (multi-threaded) and save ----------------------------
IJ.showStatus("Downloading region...")
def crop = Views.zeroMin(Views.interval(full, box))
def out = new PlanarImgFactory(type.createVariable()).create(crop)
LoopBuilder.setImages(crop, out).multiThreaded().forEachPixel({ a, b -> b.set(a) } as BiConsumer)

def imp = ImageJFunctions.wrap(out, outFile.getName().replaceAll('\\.tiff?$', ''))
imp.setDimensions(1, (int) out.dimension(2), 1)
def cal = imp.getCalibration()
cal.pixelWidth = s0[0]; cal.pixelHeight = s0[1]; cal.pixelDepth = s0[2]
cal.setUnit(unit)
cal.xOrigin = -box.min(0); cal.yOrigin = -box.min(1); cal.zOrigin = -box.min(2)  // keep global position
imp.setProperty("Info", "Source: " + url + "\nLevel: " + paths[0] +
        "\nmin=" + Arrays.toString(Intervals.minAsLongArray(box)) + "\nmax=" + Arrays.toString(Intervals.maxAsLongArray(box)))
IJ.saveAsTiff(imp, outFile.getAbsolutePath())

// Fiji passes in the path of the running script under this name.
def scriptFile = new File(binding.variables["javax.script.filename"] ?: "")
def provenance = [
    script    : "crop_remote_zarr_bdv.groovy",
    // The checksum identifies exactly which version of this script ran.
    sha256    : scriptFile.isFile() ? MessageDigest.getInstance("SHA-256").digest(scriptFile.getBytes()).encodeHex().toString() : null,
    created   : OffsetDateTime.now().toString(),
    imagej    : IJ.getFullVersion(),
    parameters: [url: url, outFile: outFile.getAbsolutePath(), nThreads: nThreads, requireCalibration: requireCalibration],
    source    : [url: url, level: paths[0], axes: names.reverse(), channel: 0, timepoint: 0],
    // Inclusive voxel indices at full resolution, in x,y,z order.
    box       : [min: Intervals.minAsLongArray(box), max: Intervals.maxAsLongArray(box)],
    voxelSize : [x: s0[0], y: s0[1], z: s0[2], unit: unit],
    outputs   : [outFile.getAbsolutePath()],
]
def provFile = new File(outFile.getParentFile(), outFile.getName().replaceAll('\\.tiff?$', '') + ".provenance.json")
provFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson(provenance)) + "\n", "UTF-8")

imp.show()
IJ.log("Saved " + outFile + " and " + provFile.getName())
