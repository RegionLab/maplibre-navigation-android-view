import UIKit

import MapLibre
import MapLibreNavigationCore
import Alamofire

class CoreOnlyViewController: UIViewController, MLNMapViewDelegate, ProgressChangeListener {
    
    private var maneuverText: UILabel!
    
    /// Held instance of MapLibre navigation. **Important** to keep this in a property to have a keep-alive instance that still works.
    /// If we use this only locally the iOS lifecycle will throw away this instance and the navigation gets stopped.
    private var maplibreNavigation: IOSMapLibreNavigation?
    
    override func viewDidLoad() {
        super.viewDidLoad()

        let mapView = MLNMapView(frame: view.bounds)
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        
        mapView.delegate = self
        mapView.styleURL = URL(
            string: "https://tiles.versatiles.org/assets/styles/colorful/style.json"
        )
        
        view.addSubview(mapView)
        
        maneuverText = UILabel(frame: CGRect(x: 16.0, y: 16.0, width: view.bounds.size.width - 32, height: 56))
        maneuverText.backgroundColor = .white
        view.addSubview(maneuverText)
        
        if let superview = maneuverText.superview {
            maneuverText.translatesAutoresizingMaskIntoConstraints = false
            maneuverText.topAnchor.constraint(equalTo: superview.safeAreaLayoutGuide.topAnchor).isActive = true
        }
    }
    
    func mapView(_ map: MLNMapView, didFinishLoading style: MLNStyle) {
        loadRoute(map: map, style: style)
    }
    
    private func loadRoute(map: MLNMapView, style: MLNStyle) {
        maneuverText.text = "Loading..."
        
        fetchRoute { directionsRoute in
            //TODO (fabi755): show error when nil!!
            let routes = directionsRoute!.routes
            let route = self.withRouteOptions(route: routes.first!)

            let navigationOptions = MapLibreNavigationOptions.Builder()
                .withSnapToRoute(snapToRoute: true)
                .build()
            let replayLocationEngine = ReplayRouteLocationEngine()
            self.maplibreNavigation = IOSMapLibreNavigation.Builder()
                .withLocationEngine(locationEngine: replayLocationEngine)
                .withOptions(options: navigationOptions)
                .build()
            
            self.maplibreNavigation?.addProgressChangeListener(progressChangeListener: self)
            
            self.drawRoute(style: style, route: route)
            self.enableLocationComponent(map: map, maplibreNavigation: self.maplibreNavigation)
            
            replayLocationEngine.assign(route: route)
            self.maplibreNavigation?.startNavigation(directionsRoute: route)
        }
    }
    
    
    private func fetchRoute(
        completion: @escaping (DirectionsResponse?) -> Void
    ) {
        let requestBody: [String : Any] = [
            "format": "osrm",
            "costing": "auto",
            "banner_instructions": true,
            "voice_instructions": true,
            "language": "en-US",
            "directions_options": [
                "units": "kilometers"
            ],
            "costing_options": [
                "auto" : [
                    "top_speed": 130
                ]
            ],
            "locations": [
                // Hannover, Germany
                [
                    "lon": 9.6935451,
                    "lat": 52.3758408,
                    "type": "break"
                ],
                // Hamburg, Germany
                [
                    "lon": 9.9769191,
                    "lat": 53.5426183,
                    "type": "break"
                ]
            ]
        ]
        
        AF.request(
            "https://valhalla1.openstreetmap.de/route",
            method: .post,
            parameters: requestBody,
            encoding: JSONEncoding.default,
            headers: ["User-Agent": "ML Nav - Android Sample App"]
        ).responseString { response in
            switch(response.result) {
            case .success(let json):
                let directionResponse = DirectionsResponse.companion.fromJson(
                    jsonString: json
                )
                completion(directionResponse)
                
                
            case .failure(let error):
                print("Error message: \(error)")
                completion(nil)
                break
            }
        }
    }
    
    private func withRouteOptions(route: DirectionsRoute) -> DirectionsRoute {
        let routeOptions = RouteOptions(
            // These dummy route options are not not used to create directions,
            // but currently they are necessary to start the navigation
            // and to use the banner & voice instructions.
            // Again, this isn't ideal, but it is a requirement of the framework.
            baseUrl: "https://valhalla.routing",
            user: "valhalla",
            profile: "valhalla",
            coordinates: [
                Position(longitude: 9.6935451, latitude: 52.3758408),
                Position(longitude: 9.9769191, latitude: 53.5426183)
            ],
            alternatives: nil,
            language: "en-US",
            radiuses: nil,
            bearings: nil,
            continueStraight: nil,
            roundaboutExits: nil,
            geometries: nil,
            overview: nil,
            steps: nil,
            annotations: nil,
            exclude: nil,
            voiceInstructions: true,
            bannerInstructions: true,
            voiceUnits: nil,
            accessToken: "",
            requestUuid: "",
            approaches: nil,
            waypointIndices: nil,
            waypointNames: nil,
            waypointTargets: nil,
            walkingOptions: nil,
            snappingClosures: nil
        )
        
        return route.toBuilder()
            .withRouteOptions(routeOptions: routeOptions)
            .build()
    }
    
    private func drawRoute(style: MLNStyle, route: DirectionsRoute) {
        let routeLine = PolylineEncoding.shared.decode(encoded: route.geometry, precision: 6)
        
        // TODO (fabi755): should be done by MapLibre GeoJSON library
        // But before this is possible, we need to update the MapLibre native iOS one to the new KMP library
        let routeFeature = MLNPolylineFeature(coordinates: routeLine.map { pt in CLLocationCoordinate2D(latitude: pt.latitude, longitude: pt.longitude) }, count: UInt(routeLine.count))
        
        let routeSource = MLNShapeSource(identifier: "route-source", shape: routeFeature, options: nil)
        style.addSource(routeSource)
        
        let routeLayer = MLNLineStyleLayer(identifier: "route-layer", source: routeSource)
        routeLayer.lineColor = NSExpression(forConstantValue: UIColor.blue)
        routeLayer.lineWidth = NSExpression(forConstantValue: 5.0)
        
        style.addLayer(routeLayer)
    }
    
    private func enableLocationComponent(map: MLNMapView, maplibreNavigation: MapLibreNavigation?) {
        guard let navigation = maplibreNavigation else { return }
        
        let navLocationManager = NavigationLocationManager()
        
        map.locationManager = navLocationManager
        navigation.addProgressChangeListener(progressChangeListener: navLocationManager)
        
        map.showsUserLocation = true
        map.showsUserHeadingIndicator = true
        map.userTrackingMode = MLNUserTrackingMode.followWithCourse
    }
    
    func onProgressChange(location: Location, routeProgress: RouteProgress) {
        guard let bannerInstruction = routeProgress.currentLegProgress.currentStep.bannerInstructions?[0] else {
            return
        }
        
        let remainingStepDistanceMeters = routeProgress.currentLegProgress.currentStepProgress.distanceRemaining
        maneuverText.text = "\(remainingStepDistanceMeters.rounded())m : \(bannerInstruction.primary.type?.text ?? "")+\(bannerInstruction.primary.modifier?.text ?? "") \(bannerInstruction.primary.text)"
    }
}
