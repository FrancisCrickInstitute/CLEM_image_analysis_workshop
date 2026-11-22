# OME-Zarr tools: testing

Record each test run here. A tool is marked **Tested** in [README.md](./README.md) once it passes the checks below on at least one 3D EM dataset and on one multichannel dataset.

## Test datasets

Add public OME-Zarr URLs used for testing (and the course datasets once available):

| Name | URL | Zarr / OME-NGFF version | Axes | Notes |
|------|-----|------------------------|------|-------|
| _to add_ | | | | |

## Checklist (per tool, per dataset)

- [ ] Script runs without errors in a freshly updated Fiji (record the Fiji / N5 versions)
- [ ] Remote data opens (`https://`, and `s3://` if relevant)
- [ ] Box selection works as described in the tool's page
- [ ] Size estimate matches the saved file
- [ ] Saved TIFF matches the same region opened directly with `File > Import > HDF5/N5/Zarr/OME-NGFF ...` (spot-check pixel values)
- [ ] Voxel size and unit are correct (`Image > Properties...`)
- [ ] Edge case: a box touching the far edge of the volume
- [ ] Edge case: Cancel at each step exits cleanly

## Results log

| Date | Tester | Tool | Dataset | Fiji version | Result / notes |
|------|--------|------|---------|--------------|----------------|
| | | | | | |
