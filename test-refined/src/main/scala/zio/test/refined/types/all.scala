package zio.test.refined.types

object all extends AllTypesInstances

trait AllTypesInstances
    extends CharInstances
    with DigitInstances
    with NetInstances
    with NumericInstances
    with StringInstance
    with TimeInstances
