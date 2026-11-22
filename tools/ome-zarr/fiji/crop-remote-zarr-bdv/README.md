# Crop remote Zarr (BigDataViewer)

**Script:** [`crop_remote_zarr_bdv.groovy`](./crop_remote_zarr_bdv.groovy)
**Status:** untested, see [TESTING.md](../../TESTING.md)

Streams a remote OME-Zarr pyramid into [BigDataViewer](https://imagej.net/plugins/bdv/) (BDV). You choose a 3D bounding box with BDV's interactive box tool, and the script then downloads only that box at full resolution and saves it as a local TIFF.

The box tool is the same one used by [MoBIE's *Select Box*](https://mobie.github.io/tools/select_box.html). MoBIE's version only writes the box coordinates to the Log window, whereas this script also downloads and saves the data.

## How it works

1. Reads the OME-Zarr `multiscales` metadata (v0.4 / Zarr v2 or v0.5 / Zarr v3) to get the resolution levels, voxel sizes and units.
2. Opens every level as a lazy, cached, multi-threaded image and shows the pyramid in BDV. Data is only fetched for the parts you look at.
3. Opens `BdvFunctions.selectBox(...)`. The box is defined in full-resolution voxel coordinates.
4. When you click **OK**, it copies the region from level 0 into memory using several threads and saves it as a TIFF. The TIFF keeps the voxel size, unit and origin offset.

## Usage

1. Open the script in Fiji's Script Editor (`File > New > Script...`, then `File > Open`). Check that the language is **Groovy**, then click **Run**.
2. Fill in the dialog:

| Field | Meaning |
|-------|---------|
| OME-Zarr URL | URL of the multiscale group |
| Output TIFF | Where to save |
| Download threads | Number of parallel chunk downloads (default 8) |

3. In BDV, navigate as usual and press `S` to adjust brightness and contrast. Resize the box with the sliders in the dialog, or by dragging its corners in the viewer.
4. Click **OK**, check the size estimate, then confirm.

## Limitations

- Non-spatial axes are fixed at index 0: only the first channel and timepoint are shown and exported.
- The box is aligned to the image axes. Rotated boxes are not supported.
- The whole crop must fit in Fiji's memory.
- If a dataset has no `coordinateTransformations`, the scales are inferred from the array shapes and the unit is set to `pixel`.

## Possible extensions

- Export all channels (keep the `c` axis and build a hyperstack).
- Read display ranges from `omero` metadata.
- Save to a local OME-Zarr or N5 instead of TIFF, for crops too large to fit in memory.
