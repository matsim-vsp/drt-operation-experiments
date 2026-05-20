# drt-operation-experiments

This code was used for the "Vulkaneifel school student traffic" study, where pre-booking was compared as booking-as-we-go.  The code was moved to the vsp contrib of matsim-libs.  In order to do this, 
the MATSim version needed to be moved up quite a bit.  For this, calls to the drt contrib needed to be adapted since they had changed.  A score and event based regression test failed afterwards.  It is not clear
if this just means different randomness, or structurally different results.

The code in this repository (drt-operation-experiments) is left for reference.  It is also referenced from the publication (I think).
