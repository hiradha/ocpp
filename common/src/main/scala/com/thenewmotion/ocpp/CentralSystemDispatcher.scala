package com.thenewmotion.ocpp

import xml.Elem
import scalaxb.{Fault => _, _}
import javax.xml.datatype.XMLGregorianCalendar
import com.thenewmotion.time.Imports._
import soapenvelope12.Body
import scalax.richAny
import Action._
import com.thenewmotion.ocpp
import com.thenewmotion.ocpp.Fault._
import ocpp.Meter.DefaultValue


/**
 * @author Yaroslav Klymko
 */
object CentralSystemDispatcher {
  def apply(body: Body, service: Version.Value => CentralSystemService, log: Any => Unit): Body = {

    implicit def faultToBody(x: soapenvelope12.Fault) = x.asBody

    val data = for {
      dataRecord <- body.any
      elem <- dataRecord.value.asInstanceOfOpt[Elem]
      action <- Action.fromElem(elem)
    } yield action -> elem

    data.headOption match {
      case None if body.any.isEmpty => ProtocolError("Body is empty")
      case None => NotSupported("No supported action found")
      case Some((action, xml)) => Version.fromBody(xml) match {
        case None => ProtocolError("Can't find an ocpp version")
        case Some(version) =>
          val reqRes = new ReqRes {
            def apply[REQ: XMLFormat, RES: XMLFormat](f: REQ => RES) = fromXMLEither[REQ](xml) match {
              case Left(msg) => ProtocolError(msg)
              case Right(req) => try {
                log(req)
                val res = f(req)
                log(res)
                simpleBody(DataRecord(Some(version.namespace), Some(action.responseLabel), res))
              } catch {
                case FaultException(fault) =>
                  log(fault)
                  fault
              }
            }
          }

          def dispatcher(service: => CentralSystemService) = version match {
            case Version.V12 => new CentralSystemDispatcherV12(action, reqRes, service)
            case Version.V15 => new CentralSystemDispatcherV15(action, reqRes, service)
          }
          dispatcher(service(version)).dispatch
      }
    }
  }
}

trait Dispatcher {
  def version: Version.Value
  def dispatch: Body
  def reqRes: ReqRes
  def ?[REQ: XMLFormat, RES: XMLFormat](f: REQ => RES): Body = reqRes(f)
  def fault(x: soapenvelope12.Fault): Nothing = throw new FaultException(x)
}

trait ReqRes {
  def apply[REQ: XMLFormat, RES: XMLFormat](f: REQ => RES): Body
}

class CentralSystemDispatcherV12(val action: Value,
                                 val reqRes: ReqRes,
                                 service: => CentralSystemService) extends Dispatcher {
  import v12._

  def version = Version.V12

  implicit def toIdTagInfo(x: ocpp.IdTagInfo): IdTagInfo = {
    val status = {
      import ocpp.{AuthorizationStatus => ocpp}
      x.status match {
        case ocpp.Accepted => AcceptedValue7
        case ocpp.IdTagBlocked => Blocked
        case ocpp.IdTagExpired => Expired
        case ocpp.IdTagInvalid => Invalid
        case ocpp.ConcurrentTx => ConcurrentTx
      }
    }
    IdTagInfo(status, x.expiryDate.map(implicitly[XMLGregorianCalendar](_)), x.parentIdTag)
  }

  def dispatch = action match {
    case Authorize => ?[AuthorizeRequest, AuthorizeResponse] {
      req => AuthorizeResponse(service.authorize(req.idTag))
    }

    case BootNotification => ?[BootNotificationRequest, BootNotificationResponse] {
      req =>
        import req._
        val ocpp.BootNotificationResponse(registrationAccepted, currentTime, heartbeatInterval) =
          service.bootNotification(
            chargePointVendor,
            chargePointModel,
            chargePointSerialNumber,
            chargeBoxSerialNumber,
            firmwareVersion,
            iccid,
            imsi,
            meterType,
            meterSerialNumber)

        val registrationStatus = if (registrationAccepted) AcceptedValue6 else RejectedValue6

        BootNotificationResponse(registrationStatus, Some(currentTime), Some(heartbeatInterval))
    }

    case DiagnosticsStatusNotification =>
      ?[DiagnosticsStatusNotificationRequest, DiagnosticsStatusNotificationResponse] {
        req =>
          val uploaded = req.status match {
            case Uploaded => true
            case UploadFailed => false
          }
          service.diagnosticsStatusNotification(uploaded)
          DiagnosticsStatusNotificationResponse()
      }

    case StartTransaction => ?[StartTransactionRequest, StartTransactionResponse] {
      req =>
        import req._
        val (transactionId, idTagInfo) = service.startTransaction(
          ocpp.ConnectorScope.fromOcpp(connectorId),
          idTag, timestamp, meterStart, None)
        StartTransactionResponse(transactionId, idTagInfo)
    }

    case StopTransaction => ?[StopTransactionRequest, StopTransactionResponse] {
      req =>
        import req._
        val idTagInfo = service.stopTransaction(transactionId, idTag, timestamp, meterStop, Nil)
        StopTransactionResponse(idTagInfo.map(implicitly[IdTagInfo](_)))
    }

    case Heartbeat => ?[HeartbeatRequest, HeartbeatResponse] {
      _ => HeartbeatResponse(service.heartbeat)
    }

    case StatusNotification => ?[StatusNotificationRequest, StatusNotificationResponse] {
      req =>
        val status = req.status match {
          case Available => ocpp.Available
          case Occupied => ocpp.Occupied
          case Unavailable => ocpp.Unavailable
          case Faulted =>
            val errorCode: Option[ocpp.ChargePointErrorCode.Value] = {
              import ocpp.{ChargePointErrorCode => ocpp}
              req.errorCode match {
                case ConnectorLockFailure => Some(ocpp.ConnectorLockFailure)
                case HighTemperature => Some(ocpp.HighTemperature)
                case Mode3Error => Some(ocpp.Mode3Error)
                case NoError => None
                case PowerMeterFailure => Some(ocpp.PowerMeterFailure)
                case PowerSwitchFailure => Some(ocpp.PowerSwitchFailure)
                case ReaderFailure => Some(ocpp.ReaderFailure)
                case ResetFailure => Some(ocpp.ResetFailure)
              }
            }
            ocpp.Faulted(errorCode, None, None)
        }
        service.statusNotification(ocpp.Scope.fromOcpp(req.connectorId), status, None, None)
        StatusNotificationResponse()
    }

    case FirmwareStatusNotification => ?[FirmwareStatusNotificationRequest, FirmwareStatusNotificationResponse] {
        req =>
          val status = {
            import ocpp.{FirmwareStatus => ocpp}
            req.status match {
              case Downloaded => ocpp.Downloaded
              case DownloadFailed => ocpp.DownloadFailed
              case InstallationFailed => ocpp.InstallationFailed
              case Installed => ocpp.Installed
            }
          }
          service.firmwareStatusNotification(status)
          FirmwareStatusNotificationResponse()
      }

    case MeterValues => ?[MeterValuesRequest, MeterValuesResponse] {
      req =>
        def toMeter(x: MeterValue): Meter = Meter(x.timestamp,List(Meter.DefaultValue(x.value)))
        service.meterValues(ocpp.Scope.fromOcpp(req.connectorId), None, req.values.map(toMeter).toList)
        MeterValuesResponse()
    }
  }
}

class CentralSystemDispatcherV15(val action: Value,
                                 val reqRes: ReqRes,
                                 service: => CentralSystemService) extends Dispatcher {
  import v15._

  def version = Version.V15

  implicit def toIdTagInfo(x: ocpp.IdTagInfo): IdTagInfoType = {
    val status = {
      import ocpp.{AuthorizationStatus => ocpp}
      x.status match {
        case ocpp.Accepted => AcceptedValue13
        case ocpp.IdTagBlocked => BlockedValue
        case ocpp.IdTagExpired => ExpiredValue
        case ocpp.IdTagInvalid => InvalidValue
        case ocpp.ConcurrentTx => ConcurrentTxValue
      }
    }
    IdTagInfoType(status, x.expiryDate.map(implicitly[XMLGregorianCalendar](_)), x.parentIdTag)
  }

  def dispatch = action match {
    case Authorize => ?[AuthorizeRequest, AuthorizeResponse] {
      req => AuthorizeResponse(service.authorize(req.idTag))
    }

    case BootNotification => ?[BootNotificationRequest, BootNotificationResponse] {
      req =>
        import req._
        val ocpp.BootNotificationResponse(registrationAccepted, currentTime, heartbeatInterval) =
          service.bootNotification(
            chargePointVendor,
            chargePointModel,
            chargePointSerialNumber,
            chargeBoxSerialNumber,
            firmwareVersion,
            iccid,
            imsi,
            meterType,
            meterSerialNumber)

        val registrationStatus = if (registrationAccepted) AcceptedValue12 else RejectedValue10

        BootNotificationResponse(registrationStatus, currentTime, heartbeatInterval)
    }

    case DiagnosticsStatusNotification =>
      ?[DiagnosticsStatusNotificationRequest, DiagnosticsStatusNotificationResponse] {
        req =>
          val uploaded = req.status match {
            case Uploaded => true
            case UploadFailed => false
          }
          service.diagnosticsStatusNotification(uploaded)
          DiagnosticsStatusNotificationResponse()
      }

    case StartTransaction => ?[StartTransactionRequest, StartTransactionResponse] {
      req =>
        import req._
        val (transactionId, idTagInfo) = service.startTransaction(
          ocpp.ConnectorScope.fromOcpp(connectorId),
          idTag, timestamp, meterStart, None)
        StartTransactionResponse(transactionId, idTagInfo)
    }

    case StopTransaction => ?[StopTransactionRequest, StopTransactionResponse] {
      req =>
        import req._
        def toMeter(x: MeterValue) = Meter(x.timestamp, x.value.map(toValue).toList)
        def toTransactionData(x: TransactionData) = ocpp.TransactionData(x.values.map(toMeter).toList)

        val idTagInfo = service.stopTransaction(
          transactionId,
          idTag,
          timestamp,
          meterStop,
          transactionData.map(toTransactionData).toList)
        StopTransactionResponse(idTagInfo.map(implicitly[IdTagInfoType](_)))
    }

    case Heartbeat => ?[HeartbeatRequest, HeartbeatResponse] {
      _ => HeartbeatResponse(service.heartbeat)
    }

    case StatusNotification => ?[StatusNotificationRequest, StatusNotificationResponse] {
      req =>
        val status = req.status match {
          case Available => ocpp.Available
          case OccupiedValue => ocpp.Occupied
          case UnavailableValue => ocpp.Unavailable
          case FaultedValue =>
            val errorCode = {
              import ocpp.{ChargePointErrorCode => ocpp}
              req.errorCode match {
                case ConnectorLockFailure => Some(ocpp.ConnectorLockFailure)
                case HighTemperature => Some(ocpp.HighTemperature)
                case Mode3Error => Some(ocpp.Mode3Error)
                case NoError => None
                case PowerMeterFailure => Some(ocpp.PowerMeterFailure)
                case PowerSwitchFailure => Some(ocpp.PowerSwitchFailure)
                case ReaderFailure => Some(ocpp.ReaderFailure)
                case ResetFailure => Some(ocpp.ResetFailure)
                case GroundFailure => Some(ocpp.GroundFailure)
                case OverCurrentFailure => Some(ocpp.OverCurrentFailure)
                case UnderVoltage => Some(ocpp.UnderVoltage)
                case WeakSignal => Some(ocpp.WeakSignal)
                case OtherError => Some(ocpp.OtherError)
              }
            }
            ocpp.Faulted(errorCode, req.info, req.vendorErrorCode)
          case Reserved => ocpp.Reserved
        }
        service.statusNotification(
          ocpp.Scope.fromOcpp(req.connectorId),
          status,
          req.timestamp.map(implicitly[DateTime](_)),
          req.vendorId)
        StatusNotificationResponse()
    }

    case FirmwareStatusNotification => ?[FirmwareStatusNotificationRequest, FirmwareStatusNotificationResponse] {
      req =>
        val status = {
          import ocpp.{FirmwareStatus => ocpp}
          req.status match {
            case Downloaded => ocpp.Downloaded
            case DownloadFailed => ocpp.DownloadFailed
            case InstallationFailed => ocpp.InstallationFailed
            case Installed => ocpp.Installed
          }
        }
        service.firmwareStatusNotification(status)
        FirmwareStatusNotificationResponse()
    }

    case MeterValues => ?[MeterValuesRequest, MeterValuesResponse] {
      req =>
        def toMeter(x: MeterValue): Meter = Meter(x.timestamp, x.value.map(toValue).toList)
        service.meterValues(ocpp.Scope.fromOcpp(req.connectorId), req.transactionId, req.values.map(toMeter).toList)
        MeterValuesResponse()
    }
  }

  def toValue(x: Value): Meter.Value = {
    def toReadingContext(x: ReadingContext): Meter.ReadingContext.Value = {
      import Meter.{ReadingContext => ocpp}
      x match {
        case InterruptionBegin => ocpp.InterruptionBegin
        case InterruptionEnd => ocpp.InterruptionEnd
        case SampleClock => ocpp.SampleClock
        case SamplePeriodic => ocpp.SamplePeriodic
        case TransactionBegin => ocpp.TransactionBegin
        case TransactionEnd => ocpp.TransactionEnd
      }
    }

    def toValueFormat(x: ValueFormat): Meter.ValueFormat.Value = {
      import Meter.{ValueFormat => ocpp}
      x match {
        case Raw => ocpp.Raw
        case SignedData => ocpp.Signed
      }
    }

    def toMeasurand(x: Measurand): Meter.Measurand.Value = {
      import Meter.{Measurand => ocpp}
      x match {
        case EnergyActiveExportRegister => ocpp.EnergyActiveExportRegister
        case EnergyActiveImportRegister => ocpp.EnergyActiveImportRegister
        case EnergyReactiveExportRegister => ocpp.EnergyReactiveExportRegister
        case EnergyReactiveImportRegister => ocpp.EnergyReactiveImportRegister
        case EnergyActiveExportInterval => ocpp.EnergyActiveExportInterval
        case EnergyActiveImportInterval => ocpp.EnergyActiveImportInterval
        case EnergyReactiveExportInterval => ocpp.EnergyReactiveExportInterval
        case EnergyReactiveImportInterval => ocpp.EnergyReactiveImportInterval
        case PowerActiveExport => ocpp.PowerActiveExport
        case PowerActiveImport => ocpp.PowerActiveImport
        case PowerReactiveExport => ocpp.PowerReactiveExport
        case PowerReactiveImport => ocpp.PowerReactiveImport
        case CurrentExport => ocpp.CurrentExport
        case CurrentImport => ocpp.CurrentImport
        case Voltage => ocpp.Voltage
        case Temperature => ocpp.Temperature
      }
    }

    def toLocation(x: Location): Meter.Location.Value = {
      import Meter.{Location => ocpp}
      x match {
        case Inlet => ocpp.Inlet
        case Outlet => ocpp.Outlet
        case v15.Body => ocpp.Body
      }
    }

    def toUnit(x: UnitOfMeasure): Meter.UnitOfMeasure.Value = {
      import Meter.{UnitOfMeasure => ocpp}
      x match {
        case Wh => ocpp.Wh
        case KWh => ocpp.Kwh
        case Varh => ocpp.Varh
        case Kvarh => ocpp.Kvarh
        case W => ocpp.W
        case KW => ocpp.Kw
        case Var => ocpp.Var
        case Kvar => ocpp.Kvar
        case Amp => ocpp.Amp
        case Volt => ocpp.Volt
        case Celsius => ocpp.Celsius
      }
    }

    Meter.Value(
      x.value,
      x.context.map(toReadingContext) getOrElse DefaultValue.readingContext,
      x.format.map(toValueFormat) getOrElse DefaultValue.format,
      x.measurand.map(toMeasurand) getOrElse DefaultValue.measurand,
      x.location.map(toLocation) getOrElse DefaultValue.location,
      x.unit.map(toUnit) getOrElse DefaultValue.unitOfMeasure)
  }
}

case class FaultException(fault: soapenvelope12.Fault) extends Exception(fault.toString)