// crop_remote_zarr_bdv.groovy
// Fiji (Groovy) script: stream a remote OME-Zarr into BigDataViewer, pick a 3D
// bounding box with BDV's interactive box tool (the same "TransformedBox"
// code MoBIE's Select Box uses), then download only that box at full
// resolution and save it as a local TIFF.
//
// Run from Fiji's Script Editor (Language > Groovy). Needs a current Fiji
// (N5 / n5-zarr / bigdataviewer-vistools are shipped with Fiji).
// Handles OME-Zarr v0.4 (Zarr v2) and v0.5 (Zarr v3) multiscales metadata.
// Non-spatial axes (c, t) are reduced to index 0 for viewing and export.

#@ String  (label="OME-Zarr URL (multiscale group)", description="https://... or s3://...") url
#@ File    (label="Output TIFF", style="save") outFile
#@ Integer (label="Download threads", value=8) nThreads

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
import net.imglib2.img.planar.PlanarImgFactory
import net.imglib2.img.display.imagej.ImageJFunctions
import ij.IJ

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
def names, unit = "pixel"
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
    if (s == null) {   // no metadata: infer from array shapes
        def d0 = attrs0.getDimensions(), dk = n5.getDatasetAttributes(p).getDimensions()
        s = spatial.collect { it >= 0 ? (double) d0[it] / dk[it] : 1.0d }
    }
    s as double[]
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
LoopBuilder.setImages(crop, out).multiThreaded().forEachPixel({ a, b -> b.set(a) } as LoopBuilder.TwoImageAction)

def imp = ImageJFunctions.wrap(out, outFile.getName().replaceAll('\\.tiff?$', ''))
imp.setDimensions(1, (int) out.dimension(2), 1)
def cal = imp.getCalibration()
cal.pixelWidth = s0[0]; cal.pixelHeight = s0[1]; cal.pixelDepth = s0[2]
cal.setUnit(unit)
cal.xOrigin = -box.min(0); cal.yOrigin = -box.min(1); cal.zOrigin = -box.min(2)  // keep global position
imp.setProperty("Info", "Source: " + url + "\nLevel: " + paths[0] +
        "\nmin=" + Arrays.toString(Intervals.minAsLongArray(box)) + "\nmax=" + Arrays.toString(Intervals.maxAsLongArray(box)))
IJ.saveAsTiff(imp, outFile.getAbsolutePath())
imp.show()
IJ.log("Saved " + outFile)
