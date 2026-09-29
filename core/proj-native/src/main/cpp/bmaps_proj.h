#ifndef BMAPS_PROJ_H
#define BMAPS_PROJ_H

typedef struct bmaps_projection bmaps_projection;

enum bmaps_projection_status {
    BMAPS_PROJECTION_OK = 0,
    BMAPS_PROJECTION_UNSUPPORTED = 1,
    BMAPS_PROJECTION_OUTSIDE = 2,
    BMAPS_PROJECTION_MISSING_OPERATION = 3,
    BMAPS_PROJECTION_ERROR = 4
};

bmaps_projection *bmaps_projection_open(void);
int bmaps_projection_transform(bmaps_projection *reader, int source, int target,
    double longitude, double latitude, double *output);
const char *bmaps_projection_operation(const bmaps_projection *reader);
void bmaps_projection_close(bmaps_projection *reader);

#endif
