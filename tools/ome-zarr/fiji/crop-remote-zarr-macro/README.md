# Crop remote Zarr (ImageJ macro)

**Script:** [`crop_remote_zarr.ijm`](./crop_remote_zarr.ijm)
**Status:** untested, see [TESTING.md](../../TESTING.md)

Opens a low-resolution level of a remote OME-Zarr image. You draw a box on it, and the macro then downloads only that region at full resolution and saves it as a local TIFF.

## How it works

1. Opens the chosen low-resolution level in full. It should be small, e.g. level `3`, which is 8× downsampled.
2. You draw a rectangle, then scroll to the first and last z-slice of your region.
3. The macro scales the box up to full-resolution voxel coordinates.
4. It calls Fiji's N5 importer (`File > Import > HDF5/N5/Zarr/OME-NGFF ...`) with `min=`/`max=` crop options, so only the chunks inside the box are downloaded.
5. It saves the result as a TIFF. If the importer didn't set a calibration, the macro sets it from the overview.

## Usage

1. Open the script in Fiji (`File > Open...`), then click **Run**. You can also drag it onto the Fiji window.
2. Fill in the dialog:

| Field | Meaning | Example |
|-------|---------|---------|
| OME-Zarr URL | URL of the multiscale group (the folder containing `.zattrs` / `zarr.json`) | `https://server/bucket/image.ome.zarr` |
| Low-res level | Array path inside the group used for choosing the region | `3` |
| Level to export | Array path to crop and save | `0` |
| XY / Z downsampling | Scale factor between the two levels (`2^level` for a standard 2× pyramid) | `8`, `8` |
| Array axes | Axis order of the arrays in Zarr order | `zyx (3D)` |
| Output TIFF | Where to save | `~/crop.tif` |

3. Follow the three prompts: draw the rectangle, choose the first z-slice, then choose the last z-slice.
4. Check the size estimate and confirm.

The crop coordinates are printed to the Log window and saved in the image's *Info* property (`Image > Show Info...`).

## Limitations

- You choose the region on a coarse overview, so precision is limited to the downsampling factor.
- To avoid reading past the edge of the array, the far edge of the box is clipped. This can trim up to *factor − 1* voxels at the edge of the volume.
- If many levels are anisotropic (e.g. no downsampling in z), you need to set the XY and Z factors yourself.
- 4D/5D: all channels are exported, but only the current timepoint.
- The whole crop must fit in Fiji's memory (`Edit > Options > Memory & Threads`).

## Macro recorder equivalent

The core call can be reused in your own macros:

```javascript
run("HDF5/N5/Zarr/OME-NGFF ... ",
    "url=[https://server/bucket/image.ome.zarr?/0] min=1000,2000,50 max=1999,2999,149");
```

`min` and `max` are inclusive voxel indices in **N5 order (x,y,z,c,t)**, which is the reverse of the Zarr axis order.
